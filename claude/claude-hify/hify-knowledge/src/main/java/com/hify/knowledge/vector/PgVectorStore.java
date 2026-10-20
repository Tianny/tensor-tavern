package com.hify.knowledge.vector;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * pgvector 向量存储：document_chunk 表（PG）的写入 / 删除 / 相似度检索。
 * 逻辑删除不适用于向量行（块没有独立业务含义，随文档物理删除），
 * 知识库维度冗余存列（knowledge_base_id），检索免跨库回查 MySQL document。
 *
 * <p>启动时自动建扩展 / 建表 / 建索引（幂等），本地起个裸 PG 容器即可用。
 * 注意：向量维度 1024 与表 DDL 绑定（text-embedding-v3），不做成配置项。
 */
@Slf4j
@Component
public class PgVectorStore {

    /** 向量维度：bailian text-embedding-v3 固定输出 1024 维（表 DDL 同步写死） */
    public static final int EMBEDDING_DIM = 1024;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 自建 Hikari 连接池，不注册成 Spring Bean——多暴露一个 DataSource/JdbcTemplate
     * Bean 会让 Spring Boot 对主库（MySQL）的自动装配退避（@ConditionalOnSingleCandidate
     * / @ConditionalOnMissingBean），业务侧无 Qualifier 的注入全部串到 PG。
     */
    public PgVectorStore(
            @Value("${hify.pgvector.url}") String url,
            @Value("${hify.pgvector.username}") String username,
            @Value("${hify.pgvector.password}") String password,
            @Value("${hify.pgvector.connection-timeout:5000}") long connectionTimeout,
            @Value("${hify.pgvector.maximum-pool-size:4}") int maxPoolSize) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setConnectionTimeout(connectionTimeout);
        dataSource.setMaximumPoolSize(maxPoolSize);
        dataSource.setMinimumIdle(1);
        dataSource.setPoolName("pgvector-pool");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 启动即建表（快速失败：PG 不可用直接起不来，避免带病运行） */
    @jakarta.annotation.PostConstruct
    public void init() {
        initSchema();
    }

    /** 建扩展 / 表 / 索引，幂等可重复执行 */
    public void initSchema() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_chunk (
                  id                BIGSERIAL PRIMARY KEY,
                  knowledge_base_id BIGINT NOT NULL,
                  document_id       BIGINT NOT NULL,
                  chunk_index       INT    NOT NULL DEFAULT 0,
                  content           TEXT   NOT NULL,
                  token_count       INT    NOT NULL DEFAULT 0,
                  embedding         VECTOR(%d),
                  created_at        TIMESTAMP NOT NULL DEFAULT now(),
                  UNIQUE (document_id, chunk_index)
                )
                """.formatted(EMBEDDING_DIM));
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding
                  ON document_chunk USING hnsw (embedding vector_cosine_ops)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_document_chunk_document_id
                  ON document_chunk (document_id)
                """);
        log.info("pgvector 向量存储就绪（维度={}）", EMBEDDING_DIM);
    }

    /** 待入库分块（含向量） */
    public record ChunkRow(int chunkIndex, String content, int tokenCount, float[] embedding) {
    }

    /** 检索命中 */
    public record ChunkHit(long documentId, int chunkIndex, String content, int tokenCount, double score) {
    }

    /** 批量写入一个文档的全部分块（先清旧，支持失败重跑全量重刷） */
    public void replaceChunks(long knowledgeBaseId, long documentId, List<ChunkRow> rows) {
        deleteByDocument(documentId);
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO document_chunk(knowledge_base_id, document_id, chunk_index, content, token_count, embedding) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                rows,
                100,
                (PreparedStatement ps, ChunkRow row) -> {
                    ps.setLong(1, knowledgeBaseId);
                    ps.setLong(2, documentId);
                    ps.setInt(3, row.chunkIndex());
                    ps.setString(4, row.content());
                    ps.setInt(5, row.tokenCount());
                    ps.setObject(6, new PGvector(row.embedding()));
                });
    }

    public void deleteByDocument(long documentId) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
    }

    public int countByDocument(long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk WHERE document_id = ?", Integer.class, documentId);
        return count == null ? 0 : count;
    }

    /** 批量计数（文档列表联显分块数），无记录的文档不在返回 Map 里 */
    public java.util.Map<Long, Integer> countByDocuments(java.util.List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return java.util.Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(documentIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT document_id, COUNT(*) AS cnt FROM document_chunk WHERE document_id IN (" + placeholders + ") "
                        + "GROUP BY document_id",
                rs -> {
                    java.util.Map<Long, Integer> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getLong("document_id"), rs.getInt("cnt"));
                    }
                    return map;
                },
                documentIds.toArray());
    }

    /** 分块预览：按块序正序 */
    public List<ChunkHit> listChunks(long documentId, int offset, int limit) {
        return jdbcTemplate.query(
                "SELECT document_id, chunk_index, content, token_count FROM document_chunk "
                        + "WHERE document_id = ? ORDER BY chunk_index LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapRow(rs, 0.0),
                documentId, limit, offset);
    }

    /** 余弦相似度 top-k：score = 1 - cosine 距离，[0,1] 越大越相似 */
    public List<ChunkHit> search(float[] queryVector, Collection<Long> knowledgeBaseIds, int topK) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        // 占位符个数随集合大小变化（KB 绑定数量级很小，拼接安全）
        String placeholders = String.join(",", java.util.Collections.nCopies(knowledgeBaseIds.size(), "?"));
        PGvector vec = new PGvector(queryVector);
        // 参数顺序与 SQL 里占位符出现顺序一致：score 表达式 → IN → ORDER BY → LIMIT
        List<Object> args = new ArrayList<>(knowledgeBaseIds.size() + 3);
        args.add(vec);
        args.addAll(knowledgeBaseIds);
        args.add(vec);
        args.add(topK);
        String sql = "SELECT c.document_id, c.chunk_index, c.content, c.token_count, "
                + "1 - (c.embedding <=> ?) AS score FROM document_chunk c "
                + "WHERE c.knowledge_base_id IN (" + placeholders + ") "
                + "ORDER BY c.embedding <=> ? LIMIT ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs, rs.getDouble("score")), args.toArray());
    }

    private ChunkHit mapRow(ResultSet rs, double score) throws SQLException {
        return new ChunkHit(rs.getLong("document_id"), rs.getInt("chunk_index"),
                rs.getString("content"), rs.getInt("token_count"), score);
    }
}
