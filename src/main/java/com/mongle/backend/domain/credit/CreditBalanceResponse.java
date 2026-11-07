package com.mongle.backend.domain.credit;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description="전체 잔액 및 유료(PAYMENT)/무료(SIGNUP,ATTENDANCE,PROMOTION)별 사용 가능·예약 수량. 예약분은 즉시 환불 가능 수량을 뜻하지 않습니다.")
public record CreditBalanceResponse(long available,long reserved,long total,boolean spendingBlocked,
        long paidAvailable,long paidReserved,long freeAvailable,long freeReserved) {}
