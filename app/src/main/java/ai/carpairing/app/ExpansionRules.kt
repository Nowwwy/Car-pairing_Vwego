package ai.carpairing.app

import java.net.URI

/** Only our exact invitation format is accepted; joining still asks the driver. */
internal object ExpansionRules {
    fun invitationCode(value:String?):String? {
        if(value==null)return null
        val uri=runCatching{URI(value)}.getOrNull()?:return null
        if(uri.scheme!="carpairing"||uri.host!="join"||uri.rawUserInfo!=null||uri.port!=-1||
            !uri.rawPath.isNullOrEmpty()||uri.rawFragment!=null)return null
        val query=uri.rawQuery?:return null
        return Regex("code=([0-9]{6})").matchEntire(query)?.groupValues?.get(1)
    }
    fun defaultDwell(kind:String)=when(kind){"rest"->15;"fuel"->10;else->0}
}
