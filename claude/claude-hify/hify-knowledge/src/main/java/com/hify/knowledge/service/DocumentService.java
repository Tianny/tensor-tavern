package com.hify.knowledge.service;

import java.util.List;

import com.hify.knowledge.dto.ChunkResp;
import com.hify.knowledge.dto.DocumentResp;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档服务接口。
 */
public interface DocumentService {

    /** 上传（txt/md 纯文本）：落盘 + 入库 pending + 触发异步向量化管线 */
    DocumentResp upload(Long knowledgeBaseId, MultipartFile file);

    /** 指定知识库的文档列表（id 倒序） */
    List<DocumentResp> list(Long knowledgeBaseId);

    /** 逻辑删除文档 + 清向量 + 删原始文件 */
    void delete(Long id);

    /** 失败文档重试（从头重跑全管线，旧向量先清） */
    void retry(Long id);

    /** 分块预览（token 数正序分页） */
    List<ChunkResp> listChunks(Long documentId, Integer offset, Integer limit);
}
