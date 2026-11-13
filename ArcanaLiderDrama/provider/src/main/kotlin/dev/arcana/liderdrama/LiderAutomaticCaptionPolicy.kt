package dev.arcana.liderdrama

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object LiderAutomaticCaptionPolicy {
    const val LIMIT=2_000_000
    fun budget(callerBudget:Long,queuedMs:Long)=minOf(25_000L,maxOf(0L,callerBudget-queuedMs))
    fun required(language:String)=language.lowercase(java.util.Locale.ROOT).substringBefore('-')in setOf("tr","tur")
    fun issuedTarget(caption:LiderContract.Caption,expectedSlug:String,expectedNumber:Int):String {
        require(caption.transport==LiderContract.CaptionTransport.ISSUED_RESOURCE)
        LiderContract.slug(expectedSlug);require(expectedNumber in 1..10000)
        require(caption.scope==(expectedSlug to expectedNumber))
        val checked=LiderContract.media(caption.url)
        require(URI(checked).host!="liderdrama.com")
        require(caption.format in setOf("vtt","srt")&&URI(checked).rawPath.lowercase(java.util.Locale.ROOT).endsWith("."+caption.format))
        return checked
    }
    fun target(raw:String,expectedSlug:String,expectedNumber:Int):String {
        val u=URI(raw)
        require(source(raw)&&u.rawQuery==null&&u.rawFragment==null)
        require(u.rawPath.matches(Regex("/subs/[A-Za-z0-9][A-Za-z0-9-]{0,239}/[1-9][0-9]{0,4}\\.(?:vtt|srt)")))
        require(expectedSlug.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,239}"))&&expectedNumber in 1..10000)
        val path="/subs/$expectedSlug/$expectedNumber"
        require(u.rawPath=="$path.vtt"||u.rawPath=="$path.srt")
        return u.toASCIIString()
    }
    fun source(raw:String)=runCatching {
        val u=URI(raw)
        u.scheme=="https"&&u.host=="liderdrama.com"&&u.rawUserInfo==null&&u.port in setOf(-1,443)
    }.getOrDefault(false)
    fun same(raw:String,target:String)=runCatching {
        val u=URI(raw);val t=URI(target)
        source(raw)&&u.rawPath==t.rawPath&&u.rawFragment==null&&
            (u.rawQuery==null||u.rawQuery.split('&').all{it.substringBefore('=').startsWith("__cf_chl_")})
    }.getOrDefault(false)
    fun navigation(raw:String,target:String)=runCatching {
        same(raw,target)||(source(raw)&&URI(raw).rawPath.startsWith("/cdn-cgi/"))
    }.getOrDefault(false)
    fun resource(raw:String,method:String,target:String)=runCatching {
        val u=URI(raw);require(u.scheme=="https"&&u.rawUserInfo==null&&u.port in setOf(-1,443))
        when {
            u.host=="challenges.cloudflare.com"->method in setOf("GET","POST")
            u.host=="liderdrama.com"&&u.rawPath.startsWith("/cdn-cgi/")->method in setOf("GET","POST")
            same(raw,target)->method=="GET"
            else->false
        }
    }.getOrDefault(false)
    fun cookie(raw:String)=raw.also{require(it.none{c->c.code<32||c.code==127})}
    fun body(status:Int,requested:String,received:String,bytes:ByteArray):String {
        require(status==200&&requested==received&&bytes.isNotEmpty()&&bytes.size<=LIMIT)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(LiderCaptionPolicy.timed(text))
        return text
    }
}

internal object LiderAutomaticCaptionAttempt {
    inline fun<T> run(permitted:()->Unit,recordFailure:()->Unit,operation:()->T):T=try {
        operation().also{permitted()}
    }catch(e:java.util.concurrent.CancellationException){throw e}
    catch(e:Exception){permitted();recordFailure();throw e}
}

/** Keep the in-memory stop even when persistence itself fails. */
internal class LiderAutomaticCaptionBackoff(
    private val read:()->Long,private val write:(Long)->Boolean,private val now:()->Long
) {
    private var until=read()
    @Synchronized fun blocked()=maxOf(until,read())>now()
    @Synchronized fun failure(duration:Long) {
        until=maxOf(until,read(),now()+duration)
        if(!write(until))throw java.io.IOException("Caption stop could not be stored")
    }
}

/** Cues only admit one real body read; they never authenticate content. */
internal class LiderAutomaticCaptionTicket {
    private var generation=0L
    private var probes=0
    private var admitted=false
    private var closed=false
    @Synchronized fun navigation():Long {generation++;return generation}
    @Synchronized fun generation()=generation
    @Synchronized fun live()=!closed
    @Synchronized fun probe(g:Long):Boolean {
        if(closed||admitted||g!=generation||probes>=4)return false
        probes++;return true
    }
    @Synchronized fun admit(g:Long):Boolean {
        if(closed||admitted||g!=generation)return false
        admitted=true;return true
    }
    @Synchronized fun close(){closed=true;generation++}
}
