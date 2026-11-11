package dev.arcana.liderdrama

import org.json.JSONObject

/** Boolean trigger only. No layout/full-text materialisation, cookie access or network. */
internal object LiderCaptionProbe {
    fun script(document:String,captionTarget:String):String {
        val expected=JSONObject.quote(document)
        val target=JSONObject.quote(captionTarget)
        return """
            (function(){
              var loc=window.location;
              if(loc.origin!=="https://liderdrama.com"||loc.href!==$expected)return false;
              var wanted=new URL($target),current=new URL(loc.href);
              if(current.pathname!==wanted.pathname)return false;
              if(Array.from(current.searchParams.keys()).some(function(k){return k.indexOf("__cf_chl_")!==0;}))return false;
              var body=window.document.body;
              if(!body||!window.performance||typeof window.performance.now!=="function")return false;
              var start=window.performance.now(),walk=window.document.createTreeWalker(body,NodeFilter.SHOW_ALL);
              var text="",visited=0,remaining=65536,node;
              while(visited<256&&remaining>0&&window.performance.now()-start<20){
                node=walk.nextNode();if(!node)break;visited++;
                if(node.nodeType!==3)continue;
                var offset=0;
                while(offset<node.length&&remaining>0&&window.performance.now()-start<20){
                  var take=Math.min(4096,remaining,node.length-offset);
                  text+=node.substringData(offset,take);
                  offset+=take;remaining-=take;
                }
              }
              text=text.replace(/^\uFEFF/,"").trimStart();
              if(text.charAt(0)==="<"||text.charAt(0)==="{")return false;
              return /(?:[0-9]{2,4}:)?[0-9]{2}:[0-9]{2}[.,][0-9]{3}\s{1,8}-->\s{1,8}(?:[0-9]{2,4}:)?[0-9]{2}:[0-9]{2}[.,][0-9]{3}/m.test(text);
            })();
        """.trimIndent()
    }
}
