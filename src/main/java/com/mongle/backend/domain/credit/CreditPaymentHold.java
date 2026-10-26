package com.mongle.backend.domain.credit;
import com.mongle.backend.global.common.BaseCreatedEntity;
import jakarta.persistence.*;
import lombok.*;
/** 외부 결제 취소 후 정합성 검토 기록. 금액을 임의로 역산하거나 잔액을 삭제하지 않는다. */
@Entity @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
@Table(name="credit_payment_holds",indexes=@Index(name="idx_credit_payment_hold_user",columnList="user_id"))
public class CreditPaymentHold extends BaseCreatedEntity {
    @Id @Column(name="grant_id") private Long grantId;
    @Column(name="user_id",nullable=false) private Long userId;
    static CreditPaymentHold create(Long grantId,Long userId) {
        var h=new CreditPaymentHold(); h.grantId=grantId; h.userId=userId; return h;
    }
}
