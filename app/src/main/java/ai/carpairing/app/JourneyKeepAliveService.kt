package ai.carpairing.app
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
class JourneyKeepAliveService:Service(){
 override fun onCreate(){
  super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("journey_sync","일행 위치·상황 공유",NotificationManager.IMPORTANCE_LOW))
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val n=Notification.Builder(this,"journey_sync").setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("Car Pairing · 일행과 연결 중").setContentText("내비게이션을 보는 동안에도 위치와 상황을 공유합니다.").setContentIntent(open).setOngoing(true).build()
  if(android.os.Build.VERSION.SDK_INT>=29)startForeground(401,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(401,n)
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onTaskRemoved(rootIntent:Intent?){stopSelf();super.onTaskRemoved(rootIntent)}
}
