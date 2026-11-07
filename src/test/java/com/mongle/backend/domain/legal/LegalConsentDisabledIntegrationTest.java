package com.mongle.backend.domain.legal;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:legal-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@ActiveProfiles("test")
class LegalConsentDisabledIntegrationTest {
    @Autowired LegalService legal;
    @Autowired UserRepository users;
    @Autowired LegalConsentRepository consents;

    @Test void unpublishedDocumentsDoNotFabricateConsentOrBlockExistingMembers() {
        var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@legal.test", "기존회원"));
        assertThat(legal.catalog().published()).isFalse();
        assertThat(legal.accessAllowed(user)).isTrue();
        assertThat(legal.response(user).termsAccepted()).isFalse();
        assertThatThrownBy(() -> legal.accept(user.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(LegalError.UNAVAILABLE));
        assertThat(consents.count()).isZero();
    }
}
