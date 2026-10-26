package com.mongle.backend.domain.world.folder;

import com.mongle.backend.global.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/** 폴더와 revision에 묶인 날짜·ID 경계. 커서는 권한 토큰이 아니다. */
public record WorldSelectionCursor(LocalDate date, long dreamId) {
    public static WorldSelectionCursor parse(String value, WorldFolder folder) {
        if (value == null) return null;
        long revision;
        WorldSelectionCursor position;
        try {
            if (value.isEmpty() || value.length() > 160) throw new IllegalArgumentException();
            var bytes = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))
                throw new IllegalArgumentException();
            var fields = new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !fields[0].equals("v1") || Long.parseLong(fields[1]) != folder.getId())
                throw new IllegalArgumentException();
            revision = Long.parseLong(fields[2]);
            var date = LocalDate.parse(fields[3]);
            long id = Long.parseLong(fields[4]);
            if (revision < 0 || id < 1 || date.getYear() < 1) throw new IllegalArgumentException();
            position = new WorldSelectionCursor(date, id);
        } catch (RuntimeException invalid) {
            throw new BusinessException(WorldFolderErrorCode.INVALID_CURSOR);
        }
        folder.checkRevision(revision);
        return position;
    }

    public static String after(WorldFolder folder, SelectedDreamResponse dream) {
        String raw = "v1|" + folder.getId() + "|" + folder.getRevision() + "|" + dream.dreamedAt() + "|" + dream.dreamId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
