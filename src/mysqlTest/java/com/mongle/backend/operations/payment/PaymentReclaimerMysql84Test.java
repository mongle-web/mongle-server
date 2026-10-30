package com.mongle.backend.operations.payment;

import javax.sql.DataSource;

class PaymentReclaimerMysql84Test extends PaymentReclaimerContract {
    @Override
    DataSource dataSource() {
        return new PaymentInspectorMysql84Test().dataSource();
    }
}
