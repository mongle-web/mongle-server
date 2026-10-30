package com.mongle.backend.domain.dream.image;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

@Schema(name = "BatchImageDownloadResponse")
public record ImageDownloadResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items) {
    public ImageDownloadResponse {
        items = List.copyOf(items);
    }

    public enum Status {
        SUCCESS,
        FAILED
    }

    @Schema(name = "BatchImageDownloadItem")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long imageId,
            @Schema(
                            description = "URL 발급에 사용한 이미지 스냅샷 버전. 찾을 수 없는 항목은 null입니다.",
                            nullable = true,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @Nullable Long imageVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Status status,
            @Schema(
                            description =
                                    "SUCCESS인 항목의 HTTPS 임시 URL. 원본 파일 주소이며 작은 썸네일을 별도 생성하지 않습니다.",
                            nullable = true,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    @Nullable String url,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
                    @Nullable Instant expiresAt,
            @Schema(
                            nullable = true,
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            description =
                                    "실패 시 IMAGE_404_1 / IMAGE_503_1 / IMAGE_502_2. SUCCESS이면"
                                            + " null입니다.")
                    @Nullable String failureCode) {
        static Item success(Long id, long version, ImageAssetStore.DownloadUrl signed) {
            return new Item(id, version, Status.SUCCESS, signed.url(), signed.expiresAt(), null);
        }

        static Item failure(Long id, @Nullable Long version, ImageErrorCode error) {
            return new Item(id, version, Status.FAILED, null, null, error.getCode());
        }

        @Override
        public String toString() {
            return "ImageDownloadItem[imageId="
                    + imageId
                    + ", imageVersion="
                    + imageVersion
                    + ", status="
                    + status
                    + ", failureCode="
                    + failureCode
                    + "]";
        }
    }
}
