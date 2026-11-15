package ai.carpairing.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Crisp local icons; no network images or font-symbol dependencies. */
internal class NavIconView(context:Context,private val kind:String,color:Int):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun onDraw(c:Canvas){
        super.onDraw(c);c.save();c.scale(width/28f,height/28f)
        when(kind){
            "mic"->{c.drawRoundRect(RectF(10f,3f,18f,18f),4f,4f,p);c.drawArc(RectF(6f,9f,22f,23f),0f,180f,false,p);c.drawLine(14f,23f,14f,26f,p);c.drawLine(10f,26f,18f,26f,p)}
            "group"->{c.drawCircle(10f,8f,4f,p);c.drawArc(RectF(2f,15f,18f,29f),180f,180f,false,p);c.drawArc(RectF(16f,4f,24f,12f),-80f,170f,false,p);c.drawArc(RectF(16f,16f,27f,29f),-100f,100f,false,p)}
            "report"->{val path=Path().apply{moveTo(4f,4f);lineTo(24f,4f);lineTo(24f,20f);lineTo(12f,20f);lineTo(6f,25f);lineTo(6f,20f);lineTo(4f,20f);close()};c.drawPath(path,p);c.drawLine(14f,8f,14f,13f,p);c.drawCircle(14f,16f,.7f,p)}
            "search"->{c.drawCircle(12f,12f,8f,p);c.drawLine(18f,18f,25f,25f,p)}
            "nav"->{c.drawPath(Path().apply{moveTo(14f,3f);lineTo(24f,25f);lineTo(14f,20f);lineTo(4f,25f);close()},p)}
            else->{for(x in listOf(6f,14f,22f))c.drawCircle(x,14f,1.6f,p)}
        };c.restore()
    }
}
