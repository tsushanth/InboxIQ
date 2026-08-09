package com.inboxiq.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromLabel(label: MessageLabel): String = label.name

    @TypeConverter
    fun toLabel(value: String): MessageLabel = MessageLabel.valueOf(value)

    @TypeConverter
    fun fromSendStatus(status: SendStatus): String = status.name

    @TypeConverter
    fun toSendStatus(value: String): SendStatus = SendStatus.valueOf(value)
}

@Database(
    entities = [MessageEntity::class, ThreadLabelEntity::class, BlockedNumberEntity::class],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun threadLabelDao(): ThreadLabelDao
    abstract fun blockedNumberDao(): BlockedNumberDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "inboxiq.db",
                )
                    // Pre-release: schema still moving, no real user data to preserve yet.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
