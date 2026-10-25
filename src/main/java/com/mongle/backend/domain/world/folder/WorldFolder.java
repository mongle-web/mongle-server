package com.mongle.backend.domain.world.folder;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.global.common.BaseEntity;
import com.mongle.backend.global.error.BusinessException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.util.Objects;

/** 무료로 편집하는 선택 목록의 경계. 완성 세계관의 구성과 AI 결과는 소유하지 않는다. */
@Getter
@Entity
@Table(name = "world_folders", indexes = @Index(name = "idx_world_folder_user_id", columnList = "user_id,id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorldFolder extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // H2는 VARCHAR 길이를 UTF-16 단위로 센다. 저장 공간은 넉넉히 두고 정책은 100 코드포인트로 검증한다.
    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "selected_dream_count", nullable = false)
    @ColumnDefault("0")
    private long selectedDreamCount;

    @Version @Column(nullable = false) @ColumnDefault("0")
    private long revision;

    public static WorldFolder create(User user, String name) {
        var folder = new WorldFolder();
        folder.user = Objects.requireNonNull(user);
        folder.rename(name);
        return folder;
    }

    public void rename(String value) {
        if (value == null || value.codePointCount(0, value.length()) > 100
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new BusinessException(WorldFolderErrorCode.INVALID_NAME);
        }
        name = value;
    }

    public void checkRevision(Long expected) {
        if (expected == null || expected != revision)
            throw new BusinessException(WorldFolderErrorCode.VERSION_CONFLICT);
    }

    public void selected() { selectedDreamCount++; }

}
