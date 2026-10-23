package app.bilimusic

import android.app.Application
import androidx.room.Room
import app.bilimusic.data.AppDb
import app.bilimusic.data.BiliApi
import app.bilimusic.data.Session
import app.bilimusic.player.PlayerConnection

class App : Application() {
    val session by lazy { Session(this) }
    val api by lazy { BiliApi(session) }
    val db by lazy { Room.databaseBuilder(this, AppDb::class.java, "bilimusic.db").build() }
    val player by lazy { PlayerConnection(this) }
}
