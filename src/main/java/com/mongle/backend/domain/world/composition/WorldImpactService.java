package com.mongle.backend.domain.world.composition;
import com.mongle.backend.domain.dream.repository.DreamRepository;
import com.mongle.backend.domain.dream.exception.DreamErrorCode;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class WorldImpactService {
    private final DreamRepository dreams;
    private final WorldCompositionQueries queries;
    public WorldImpactPage impacts(Long user,Long dream,Long before,int size) {
        dreams.findByIdAndUserId(dream,user).orElseThrow(()->new BusinessException(DreamErrorCode.NOT_FOUND));
        return queries.impacts(user,dream,before,size);
    }
}
