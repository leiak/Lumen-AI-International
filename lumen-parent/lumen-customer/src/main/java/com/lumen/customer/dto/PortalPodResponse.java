package com.lumen.customer.dto;
public record PortalPodResponse(
    Long doId, String podType,
    String imageUrl,        // 照片 URL (相对路径)
    String signatureBase64, // 签名 base64 (签名场景)
    String signerName,
    String signedAt) {}