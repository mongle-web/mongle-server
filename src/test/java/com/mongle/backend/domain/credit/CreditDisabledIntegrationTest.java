package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.util.UUID;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:credit-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class CreditDisabledIntegrationTest {
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired CreditProperties properties;
    @Autowired UserRepository users;
    @Test void defaultsAllowReadButNeverGrantOrReserveCredits(){
        Long u=users.saveAndFlush(User.create(UUID.randomUUID()+"@disabled.test","비활성")).getId();
        assertThat(properties.enabled()).isFalse();assertThat(properties.signupAmount()).isNull();
        assertThat(credits.balance(u)).isEqualTo(new CreditBalance(0,0,0));
        assertThat(credits.transactions(u,null,20).items()).isEmpty();
        assertThatThrownBy(()->credits.grant(u,CreditSource.PAYMENT,"order",10)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.UNAVAILABLE));
        assertThatThrownBy(()->reservations.reserve(u,"attempt",CreditUsage.DREAM_IMAGE_GENERATION,"image:1",Instant.now().plusSeconds(60))).isInstanceOf(BusinessException.class);
    }
}
