package com.hify.knowledge.dto;

import com.hify.knowledge.vector.PgVectorStore;

import lombok.Data;

/**
 * 分块预览。
 */
@Data
public class ChunkResp {

    private Integer chunkIndex;

    private String content;

    private Integer tokenCount;

    public static ChunkResp of(PgVectorStore.ChunkHit hit) {
        ChunkResp resp = new ChunkResp();
        resp.setChunkIndex(hit.chunkIndex());
        resp.setContent(hit.content());
        resp.setTokenCount(hit.tokenCount());
        return resp;
    }
}
