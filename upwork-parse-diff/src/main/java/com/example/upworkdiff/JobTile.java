package com.example.upworkdiff;

/** Один распознанный заказ. */
public record JobTile(
        String uid,
        String title,
        String url,
        String type,      // "hourly" | "fixed" | ""
        String budget,    // fixed: сумма; hourly: "min-max"; может быть ""
        String proposals, // "0-4" | "5-10" | "10-15" | число | ""
        String rating,    // число или ""
        String spent,     // число (уже без $ и k-суффикса развёрнут) или ""
        boolean paymentVerified, // li data-test=payment-verified есть в тайле
        long postedMillis, // приблизительная метка по тексту «X ago» (для сортировки)
        String postedText, // исходный текст, как на странице
        String query      // имя файла без расширения
) {}
