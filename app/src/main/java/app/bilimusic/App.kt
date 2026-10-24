package app.bilimusic

import android.app.Application
import androidx.room.Room
import app.bilimusic.data.*
import app.bilimusic.player.PlayerConnection

class App : Application() {
    val session by lazy { Session(this) }
    val api by lazy { BiliApi(session) }
    val db by lazy {
        Room.databaseBuilder(this, AppDb::class.java, "bilimusic.db").addMigrations(MIGRATION_1_2).build()
    }
    val prefs by lazy { Prefs(this) }
    val downloader by lazy { Downloader(this, api, db.dao(), prefs) }
    val player by lazy { PlayerConnection(this) }
}
