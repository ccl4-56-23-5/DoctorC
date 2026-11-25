package com.wirelessalien.zipxtract.nativeui
import android.content.Context
import com.wirelessalien.zipxtract.helper.AppEvent

/** Only the current in-process operation survives; no history or completed tasks are retained. */
object ActiveOperation {
    private var running=false
    var progress=0;private set
    var phase="";private set
    @Synchronized fun init(context:Context){
        context.getSharedPreferences("native_tasks",0).edit().clear().apply()
        CreationOptions.retireStoredOptions(context)
    }
    @Synchronized fun busy()=running
    @Synchronized fun start(title:String,detail:String){running=true;progress=0;phase=title}
    @Synchronized fun event(event:AppEvent){
        when(event){
            is AppEvent.ArchiveProgress->{progress=event.progress;phase=event.phase?:"正在处理 $progress%"}
            is AppEvent.ExtractionProgress->{progress=event.progress;phase="正在解压 $progress%"}
            is AppEvent.ArchiveComplete,is AppEvent.ExtractionComplete,is AppEvent.ArchiveError,is AppEvent.ExtractionError,
            is AppEvent.PhotoTimeSyncComplete->{running=false;progress=0;phase=""}
            is AppEvent.RenameComplete,AppEvent.LanFilesReceived->Unit
        }
    }
}
