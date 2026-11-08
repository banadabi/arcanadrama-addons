package dev.arcana.dramacix

/** Source segment resynchronisation only; never treats text/manifests or MP4 as transport stream. */
internal object DramacixTsPrefix {
    const val PROBE=16_384
    private const val MAX_PREFIX=8192
    fun skip(b:ByteArray):Int {
        if(b.size<377)return 0
        val text=String(b,0,minOf(b.size,64),Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
        if(listOf("#EXTM3U","WEBVTT","<?xml","<MPD","<!DOCTYPE","<html","{","[").any { text.startsWith(it,true) })return 0
        if(b.size>=8 && String(b,4,4,Charsets.US_ASCII) in setOf("ftyp","styp","moof","moov","sidx"))return 0
        // Sync establishes framing. Header flags are the demuxer's job: rejecting TEI or
        // reserved header bits here can skip the first PAT/PMT and leave no tracks.
        fun packet(p:Int)=p+3<b.size && b[p].toInt()and 255==0x47
        if(packet(0)&&packet(188)&&packet(376))return 0
        val png=b.size>=8 && b.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val jpg=b.size>=3 && b[0].toInt()and 255==255 && b[1].toInt()and 255==216 && b[2].toInt()and 255==255
        val packets=if(png||jpg)3 else 5
        val max=minOf(MAX_PREFIX,b.size-(packets-1)*188-4)
        for(p in 1..max)if((0 until packets).all { packet(p+it*188) })return p
        return 0
    }
    /** Bounded numeric-only metadata from the existing probe; never exports payload or addresses. */
    fun packetSummary(b:ByteArray,offset:Int):String {
        val pids=linkedSetOf<Int>();val types=linkedSetOf<Int>();val maps=linkedSetOf<Int>()
        var packets=0;var tei=0;var scrambled=0;var invalid=0;var pat=0;var pmt=0
        fun u(i:Int)=b[i].toInt() and 255
        var p=offset
        while(p>=0 && p+188<=b.size && packets<88) {
            if(u(p)!=0x47)break
            packets++;val pid=((u(p+1)and 31)shl 8)or u(p+2);pids.add(pid)
            if(u(p+1)and 128!=0)tei++
            if(u(p+3)and 192!=0)scrambled++
            val af=(u(p+3)shr 4)and 3
            if(af==0)invalid++
            var q=p+4
            if(af==3)q+=1+u(q)
            if(af in setOf(1,3) && u(p+1)and 64!=0 && q<p+188) {
                q+=1+u(q)
                if(q+3<=p+188) {
                    val end=q+3+(((u(q+1)and 15)shl 8)or u(q+2))
                    if(end<=p+188 && end>=q+12 && u(q+1)and 128!=0) {
                        if(pid==0 && u(q)==0) {
                            pat++;var e=q+8
                            while(e+4<=end-4) { if((u(e)shl 8)or u(e+1)!=0)maps.add(((u(e+2)and 31)shl 8)or u(e+3));e+=4 }
                        }
                        if(pid in maps && u(q)==2) {
                            pmt++;var e=q+12+(((u(q+10)and 15)shl 8)or u(q+11))
                            while(e+5<=end-4) { types.add(u(e));e+=5+(((u(e+3)and 15)shl 8)or u(e+4)) }
                        }
                    }
                }
            }
            p+=188
        }
        return "offset=$offset packets=$packets pids=${pids.take(16).joinToString(",")} tei=$tei scrambled=$scrambled reserved=$invalid pat=$pat pmt=$pmt streamTypes=${types.take(16).joinToString(",")}"
    }

}
