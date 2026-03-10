package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分片服务
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    @Autowired
    private DocumentChunkConfig chunkConfig;

    /**
     * 将文档内容分片
     *
     * @param content 文档内容
     * @param filePath 文件路径
     * @return 分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        int maxSize = chunkConfig.getMaxSize();
        int overlap = chunkConfig.getOverlap();
        int contentLength = content.length();
        int chunkIndex = 0;
        int startIndex = 0;

        while (startIndex < contentLength) {
            // 计算当前分片的结束位置
            int endIndex = Math.min(startIndex + maxSize, contentLength);

            // 提取分片内容
            String chunkContent = content.substring(startIndex, endIndex);

            // 创建分片对象
            DocumentChunk chunk = new DocumentChunk(chunkContent, startIndex, endIndex, chunkIndex);
            chunks.add(chunk);

            logger.debug("创建分片 {}: startIndex={}, endIndex={}, length={}",
                chunkIndex, startIndex, endIndex, chunkContent.length());

            // 移动到下一个分片的起始位置（考虑重叠）
            startIndex = endIndex - overlap;

            // 如果剩余内容太少，直接结束
            if (startIndex >= contentLength - overlap) {
                break;
            }

            chunkIndex++;
        }

        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }
}
