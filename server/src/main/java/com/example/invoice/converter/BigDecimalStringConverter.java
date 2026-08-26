package com.example.invoice.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;

/** BigDecimal &lt;-&gt; TEXT 双向转换。SQLite 无 DECIMAL 类型，Hibernate SQLiteDialect 默认把
 *  BigDecimal 存为 REAL（IEEE-754 double），会出现 178.00 → 178.00000000000003 这类金额漂移。
 *  改用 toPlainString() 写 TEXT、{@code new BigDecimal(s)} 读回，保证金额精度零损失。 */
@Converter(autoApply = false)
public class BigDecimalStringConverter implements AttributeConverter<BigDecimal, String> {

    @Override
    public String convertToDatabaseColumn(BigDecimal attribute) {
        return attribute == null ? null : attribute.toPlainString();
    }

    @Override
    public BigDecimal convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        return new BigDecimal(dbData);
    }
}
