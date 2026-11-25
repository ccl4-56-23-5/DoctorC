package com.wirelessalien.zipxtract
import android.app.Application
import com.wirelessalien.zipxtract.lan.LanForeground
import com.wirelessalien.zipxtract.nativeui.ActiveOperation
class App:Application(){
    internal val lan by lazy { LanForeground(this) }
    override fun onCreate(){super.onCreate();ActiveOperation.init(this)}
}
