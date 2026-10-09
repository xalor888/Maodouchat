package com.maodouchat.data.local

import androidx.room.migration.Migration

/** Room 版本迁移（自 AppDatabase.kt 抽出，A03）。AppDatabase 只负责创建/注册/事务边界。 */
internal object DatabaseMigrations {
    internal val MIGRATION_5_6: Migration get() = DatabaseMigrationsEarly.MIGRATION_5_6
    internal val MIGRATION_6_7: Migration get() = DatabaseMigrationsEarly.MIGRATION_6_7
    internal val MIGRATION_7_8: Migration get() = DatabaseMigrationsEarly.MIGRATION_7_8
    internal val MIGRATION_8_9: Migration get() = DatabaseMigrationsEarly.MIGRATION_8_9
    internal val MIGRATION_9_10: Migration get() = DatabaseMigrationsEarly.MIGRATION_9_10
    internal val MIGRATION_10_11: Migration get() = DatabaseMigrationsEarly.MIGRATION_10_11
    internal val MIGRATION_11_12: Migration get() = DatabaseMigrationsEarly.MIGRATION_11_12
    internal val MIGRATION_12_13: Migration get() = DatabaseMigrationsEarly.MIGRATION_12_13
    internal val MIGRATION_13_14: Migration get() = DatabaseMigrationsEarly.MIGRATION_13_14
    internal val MIGRATION_14_15: Migration get() = DatabaseMigrationsEarly.MIGRATION_14_15
    internal val MIGRATION_15_16: Migration get() = DatabaseMigrationsMiddle.MIGRATION_15_16
    internal val MIGRATION_16_17: Migration get() = DatabaseMigrationsMiddle.MIGRATION_16_17
    internal val MIGRATION_17_18: Migration get() = DatabaseMigrationsMiddle.MIGRATION_17_18
    internal val MIGRATION_18_19: Migration get() = DatabaseMigrationsMiddle.MIGRATION_18_19
    internal val MIGRATION_19_20: Migration get() = DatabaseMigrationsMiddle.MIGRATION_19_20
    internal val MIGRATION_20_21: Migration get() = DatabaseMigrationsMiddle.MIGRATION_20_21
    internal val MIGRATION_21_22: Migration get() = DatabaseMigrationsMiddle.MIGRATION_21_22
    internal val MIGRATION_22_23: Migration get() = DatabaseMigrationsMiddle.MIGRATION_22_23
    internal val MIGRATION_23_24: Migration get() = DatabaseMigrationsMiddle.MIGRATION_23_24
    internal val MIGRATION_24_25: Migration get() = DatabaseMigrationsMiddle.MIGRATION_24_25
    internal val MIGRATION_25_26: Migration get() = DatabaseMigrationsLate.MIGRATION_25_26
    internal val MIGRATION_26_27: Migration get() = DatabaseMigrationsLate.MIGRATION_26_27
    internal val MIGRATION_27_28: Migration get() = DatabaseMigrationsLate.MIGRATION_27_28
    internal val MIGRATION_28_29: Migration get() = DatabaseMigrationsLate.MIGRATION_28_29
    internal val MIGRATION_29_30: Migration get() = DatabaseMigrationsLate.MIGRATION_29_30
    internal val MIGRATION_30_31: Migration get() = DatabaseMigrationsLate.MIGRATION_30_31
    internal val MIGRATION_31_32: Migration get() = DatabaseMigrationsLate.MIGRATION_31_32
    internal val MIGRATION_32_33: Migration get() = DatabaseMigrationsLate.MIGRATION_32_33
    internal val MIGRATION_34_35: Migration get() = DatabaseMigrationsLate.MIGRATION_34_35
    internal val MIGRATION_33_34: Migration get() = DatabaseMigrationsLate.MIGRATION_33_34
    internal val MIGRATION_35_36: Migration get() = DatabaseMigrationsRecent.MIGRATION_35_36
    internal val MIGRATION_36_37: Migration get() = DatabaseMigrationsRecent.MIGRATION_36_37
    internal val MIGRATION_37_38: Migration get() = DatabaseMigrationsRecent.MIGRATION_37_38
    internal val MIGRATION_38_39: Migration get() = DatabaseMigrationsRecent.MIGRATION_38_39
    internal val MIGRATION_39_40: Migration get() = DatabaseMigrationsRecent.MIGRATION_39_40

    val ALL: List<Migration> = listOf(
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40,
    )
}
