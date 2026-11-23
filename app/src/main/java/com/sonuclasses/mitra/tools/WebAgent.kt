package com.sonuclasses.mitra.tools

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.sonuclasses.mitra.core.Gemini
import com.sonuclasses.mitra.ui.BrowserActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Opens websites in Mitra's own browser and searches / reads / fills forms by reading the page's
 * HTML directly. Works without the Accessibility permission and only touches Mitra's own window.
 */
object WebAgent {

    private const val MAX_STEPS = 30

    private val BLOCKED_HOSTS = listOf(
        "paytm", "phonepe", "pay.google", "bhimupi", "onlinesbi", "netbanking", "razorpay", "payu",
        "ccavenue", "billdesk", "paypal", "icicibank", "hdfcbank", "axisbank", "kotak", "sbi.co.in",
        "pnbindia", "bankofbaroda", "cashfree", "instamojo", "stripe.com"
    )

    private val RULES = """
तुम मित्र AI का वेब-सहायक हो। मित्र के अपने ब्राउज़र में एक वेबसाइट खुली है और तुम्हें यूज़र का काम (goal) पूरा करना है।
हर कदम पर तुम्हें पेज का पता, शीर्षक, पेज पर लिखा टेक्स्ट और काम के तत्वों की सूची मिलेगी:
[id] प्रकार "टेक्स्ट/लेबल" value=.. *ज़रूरी (नीचे/ऊपर = अभी स्क्रीन से बाहर, फिर भी चल जाएगा)। कभी-कभी पेज की तस्वीर भी।
सिर्फ़ एक JSON ऑब्जेक्ट लौटाओ (कोई और टेक्स्ट नहीं):
{"thought":"छोटा कारण","action":"<action>","id":<number या -1>,"text":"<टेक्स्ट>","say":"यूज़र को बताने/पूछने वाली बात"}
action इनमें से एक:
- open: text में वेबसाइट का पता (जैसे sarkariresult.com) या Google पर खोजने वाले शब्द
- tap: id वाले तत्व (लिंक, बटन, चेकबॉक्स, रेडियो) पर टैप
- type: id वाले बॉक्स में text लिखो (पुराना हट जाएगा)। तारीख वाले (type=date) बॉक्स में YYYY-MM-DD
- select: id वाली ड्रॉपडाउन (select) में text वाला विकल्प चुनो
- enter: id वाले बॉक्स में Enter दबाओ (सिर्फ़ सर्च बॉक्स के लिए)
- scroll_down / scroll_up
- back: पिछला पेज
- wait: पेज लोड होने दो
- done: काम पूरा — say में हिंदी में 1-3 छोटे वाक्य: क्या किया, और अगर यूज़र ने कुछ पढ़ने/ढूँढने को कहा था तो पेज से उसका जवाब
- need_user: यूज़र से कुछ पूछना/पुष्टि लेनी है — say में साफ़ सवाल
नियम:
1. कभी पेमेंट, UPI, कार्ड, बैंक, OTP, पासवर्ड, कैप्चा भरना या अकाउंट डिलीट नहीं। ऐसा आए तो need_user (कहो कि यह हिस्सा यूज़र खुद ब्राउज़र में भर दें, फिर बताएँ)।
2. फ़ॉर्म में जो जानकारी goal या "यूज़र के बारे में" में नहीं है उसे गढ़ो मत — need_user से एक बार में सारी कमी वाली जानकारी पूछो।
3. आख़िरी Submit / जमा करें / Register / Apply / Send / Pay जैसा बटन दबाने से पहले, confirmed=false हो तो need_user से पूछो और भरी हुई जानकारी छोटे में बताओ। confirmed=true हो तो दबा दो।
4. सर्च करना, लिंक खोलना, पढ़ना, फ़ॉर्म के खाने भरना — इनमें पुष्टि नहीं चाहिए।
5. हर खाने के id पर अलग-अलग type करो; ड्रॉपडाउन select हो तो select, नहीं तो उस पर tap करके विकल्प tap करो।
6. Google पर कुछ ढूँढना हो तो सीधे open में खोज के शब्द दो। नतीजों में सही लिंक tap करो।
7. वही कदम बार-बार काम न करे तो दूसरा तरीका आज़माओ। पॉप-अप/विज्ञापन/कुकी बैनर आए तो Close/✕/Accept से हटाओ।
8. काम पूरा होते ही done।
""".trimIndent()

    private const val SNAP_JS = """
(function(){
 try{
 document.querySelectorAll('[data-mitra]').forEach(function(e){e.removeAttribute('data-mitra')});
 var sel='a[href],button,input,textarea,select,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=option],[role=menuitem],[role=combobox],[contenteditable=true],[onclick],summary';
 var els=document.querySelectorAll(sel), H=window.innerHeight, out=[], n=0;
 function clean(s){return (s||'').replace(/\s+/g,' ').trim();}
 function lab(e){
   var t='';
   if(e.labels&&e.labels.length) t=e.labels[0].innerText;
   if(!t) t=e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||'';
   if(!t&&e.id){var l=document.querySelector('label[for="'+e.id+'"]'); if(l) t=l.innerText;}
   if(!t){var p=e.closest('div,td,li,p'); if(p){var c=clean(p.innerText); if(c.length<80) t=c;}}
   if(!t) t=e.name||e.id||'';
   return clean(t).slice(0,60);
 }
 for(var i=0;i<els.length&&n<160;i++){
   var e=els[i]; var r=e.getBoundingClientRect();
   if(r.width<2||r.height<2) continue;
   var st=getComputedStyle(e); if(st.visibility=='hidden'||st.display=='none'||st.opacity=='0') continue;
   var tag=e.tagName.toLowerCase(), ty=(e.getAttribute('type')||'').toLowerCase();
   if(tag=='input'&&ty=='hidden') continue;
   e.setAttribute('data-mitra',n);
   var pos=r.bottom<0?' (ऊपर)':(r.top>H?' (नीचे)':'');
   var line='['+n+'] ';
   if(tag=='input'||tag=='textarea'){
     if(ty=='checkbox'||ty=='radio') line+=ty+' "'+lab(e)+'"'+(e.checked?' ✔':'');
     else if(ty=='submit'||ty=='button') line+='button "'+clean(e.value||lab(e)).slice(0,60)+'"';
     else if(ty=='file') line+='file-upload "'+lab(e)+'"';
     else line+=(tag=='textarea'?'textarea':'input type='+(ty||'text'))+' "'+lab(e)+'" value="'+(ty=='password'?'***':clean(e.value).slice(0,40))+'"';
     if(e.required) line+=' *ज़रूरी';
     if(e.disabled) line+=' (बंद)';
   } else if(tag=='select'){
     var o=[]; for(var j=0;j<e.options.length&&j<25;j++) o.push(clean(e.options[j].text).slice(0,30));
     line+='select "'+lab(e)+'" चुना="'+clean(e.options[e.selectedIndex]?e.options[e.selectedIndex].text:'')+'" विकल्प: '+o.join(' | ');
     if(e.required) line+=' *ज़रूरी';
   } else if(e.isContentEditable){
     line+='textbox "'+lab(e)+'" value="'+clean(e.innerText).slice(0,40)+'"';
   } else {
     var t=clean(e.innerText||e.getAttribute('aria-label')||e.getAttribute('title')||'');
     if(!t){var im=e.querySelector('img[alt]'); if(im) t=clean(im.alt);}
     if(!t) { e.removeAttribute('data-mitra'); continue; }
     line+=(tag=='a'?'link':'button')+' "'+t.slice(0,70)+'"';
   }
   out.push(line+pos); n++;
 }
 var txt=clean(document.body?document.body.innerText:'').slice(0,2500);
 return JSON.stringify({url:location.href,title:document.title,text:txt,items:out});
 }catch(err){return JSON.stringify({url:location.href,title:document.title,text:'',items:[],error:String(err)});}
})()
"""

    private fun actJs(action: String, id: Int, text: String): String {
        val t = JSONObject.quote(text)
        val q = "var e=document.querySelector('[data-mitra=\"$id\"]'); if(!e) return 'missing';"
        val body = when (action) {
            "tap" -> "$q e.scrollIntoView({block:'center'}); try{e.focus();}catch(x){} e.click(); return 'ok';"
            "type" -> """$q var ty=(e.getAttribute('type')||'').toLowerCase(); if(ty=='password') return 'password';
                e.scrollIntoView({block:'center'}); e.focus();
                if(e.isContentEditable){ e.innerText=$t; }
                else { var pr=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                  Object.getOwnPropertyDescriptor(pr,'value').set.call(e,$t); }
                e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
                return 'ok';"""
            "select" -> """$q if(e.tagName!='SELECT') return 'notselect'; var w=$t.toLowerCase().trim(), k=-1;
                for(var i=0;i<e.options.length;i++){ var o=e.options[i]; var s=o.text.toLowerCase().trim();
                  if(s==w||o.value.toLowerCase()==w){k=i;break;} if(k<0&&s.indexOf(w)>=0) k=i; }
                if(k<0) return 'nooption'; e.selectedIndex=k;
                e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); return 'ok';"""
            "enter" -> """var e=$id>=0?document.querySelector('[data-mitra="$id"]'):document.activeElement; if(!e) return 'missing';
                ['keydown','keypress','keyup'].forEach(function(k){ e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true})); });
                if(e.form){ if(e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); } return 'ok';"""
            "scroll_down" -> "window.scrollBy(0, window.innerHeight*0.8); return 'ok';"
            "scroll_up" -> "window.scrollBy(0, -window.innerHeight*0.8); return 'ok';"
            else -> "return 'unknown';"
        }
        return "(function(){ try{ $body }catch(err){ return 'error:'+err; } })()"
    }

    private suspend fun eval(b: BrowserActivity, js: String): String = withContext(Dispatchers.Main) {
        withTimeoutOrNull(8000) {
            suspendCancellableCoroutine<String> { c ->
                try {
                    b.web.evaluateJavascript(js) { r -> if (c.isActive) c.resume(r ?: "null") }
                } catch (e: Exception) { if (c.isActive) c.resume("null") }
            }
        } ?: "null"
    }.let { raw ->
        try { (JSONTokener(raw).nextValue() as? String) ?: raw } catch (e: Exception) { raw }
    }

    private suspend fun waitLoaded() {
        delay(700)
        var n = 0
        while (BrowserActivity.loading && n < 20) { delay(500); n++ }
        delay(500)
    }

    private suspend fun openBrowser(ctx: Context, url: String?): BrowserActivity? {
        val existing = BrowserActivity.current
        if (existing != null && url.isNullOrBlank()) {
            // bring it to front
            ctx.startActivity(Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            return existing
        }
        val i = Intent(ctx, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (!url.isNullOrBlank()) i.putExtra(BrowserActivity.EXTRA_URL, url)
        withContext(Dispatchers.Main) { ctx.startActivity(i) }
        repeat(25) { BrowserActivity.current?.let { return it }; delay(200) }
        return BrowserActivity.current
    }

    private fun blocked(url: String): Boolean {
        val host = try { android.net.Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return BLOCKED_HOSTS.any { host.contains(it) }
    }

    suspend fun run(ctx: Context, goal: String, url: String?, confirmed: Boolean, facts: String): JSONObject {
        val b = openBrowser(ctx, url)
            ?: return err("मित्र का ब्राउज़र खुल नहीं पाया। मित्र AI ऐप खोलकर फिर से कहिए।")
        withContext(Dispatchers.Main) { b.setStatus("🤖 मित्र काम कर रहा है… (आप बीच में खुद भी भर सकते हैं)") }
        try {
            waitLoaded()
            val history = ArrayList<String>()
            var lastSig = ""
            var same = 0
            for (step in 1..MAX_STEPS) {
                if (BrowserActivity.current !== b) return err("ब्राउज़र बंद कर दिया गया, इसलिए काम रोक दिया।")
                val snap = try { JSONObject(eval(b, SNAP_JS)) } catch (e: Exception) { JSONObject() }
                val pageUrl = snap.optString("url")
                if (blocked(pageUrl)) return JSONObject().put("status", "needs_confirmation")
                    .put("message", "यह पेमेंट या बैंक का पेज है, मित्र यहाँ कुछ नहीं भरता। यूज़र को बताओ कि यह हिस्सा वे खुद ब्राउज़र में पूरा करें।")
                val items = snap.optJSONArray("items") ?: JSONArray()
                val list = (0 until items.length()).joinToString("\n") { items.getString(it) }
                val sig = pageUrl + "|" + list.hashCode()
                if (sig == lastSig) same++ else same = 0
                lastSig = sig
                val shot = if (step == 1 || same >= 1 || items.length() < 4)
                    withContext(Dispatchers.Main) { b.screenshot() } else null

                val prompt = buildString {
                    append("goal: ").append(goal).append('\n')
                    append("confirmed: ").append(confirmed).append('\n')
                    if (facts.isNotBlank()) append("यूज़र के बारे में: ").append(facts).append('\n')
                    append("कदम ").append(step).append('/').append(MAX_STEPS).append('\n')
                    append("पेज: ").append(pageUrl).append(" — ").append(snap.optString("title")).append('\n')
                    if (history.isNotEmpty()) append("अब तक के कदम:\n").append(history.takeLast(10).joinToString("\n")).append('\n')
                    if (same >= 2) append("ध्यान दो: पिछले कदमों से पेज नहीं बदला, दूसरा तरीका आज़माओ।\n")
                    append("पेज का टेक्स्ट:\n").append(snap.optString("text").ifBlank { "(खाली — शायद लोड हो रहा है)" }).append('\n')
                    append("तत्व:\n").append(list.ifBlank { "(कोई नहीं)" })
                }
                val parts = JSONArray()
                if (shot != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                    .put("data", Base64.encodeToString(shot, Base64.NO_WRAP))))
                parts.put(JSONObject().put("text", prompt))
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", RULES))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))

                val a = try { parse(Gemini.textOf(Gemini.generate(body))) } catch (e: Exception) {
                    return err("AI से जुड़ नहीं पाए: ${e.message}")
                }
                if (a == null) { history.add("$step. (AI का जवाब समझ नहीं आया)"); continue }

                val action = a.optString("action")
                val id = a.optInt("id", -1)
                val text = a.optString("text")
                val say = a.optString("say")
                when (action) {
                    "done" -> return JSONObject().put("status", "ok").put("message", say.ifBlank { "काम हो गया" })
                    "need_user" -> return JSONObject().put("status", "needs_confirmation")
                        .put("message", "ब्राउज़र में रुका हूँ। यूज़र से पूछो: $say — जवाब मिलने पर web_task को url खाली छोड़कर, वही goal (जवाब जोड़कर) और ज़रूरत हो तो confirmed=true के साथ दोबारा बुलाओ; पेज वहीं से आगे चलेगा।")
                }
                withContext(Dispatchers.Main) { b.setStatus("🤖 मित्र: ${a.optString("thought").take(70)}") }
                val res = when (action) {
                    "open" -> if (blocked(text)) "blocked" else { withContext(Dispatchers.Main) { b.load(text) }; "ok" }
                    "back" -> withContext(Dispatchers.Main) { if (b.web.canGoBack()) { b.web.goBack(); "ok" } else "no-back" }
                    "wait" -> "ok"
                    "tap", "type", "select", "enter", "scroll_down", "scroll_up" -> eval(b, actJs(action, id, text))
                    else -> "unknown"
                }
                val note = when {
                    res == "ok" -> "हुआ"
                    res == "password" -> "नहीं हुआ (पासवर्ड मित्र नहीं भरता — need_user)"
                    res == "blocked" -> "नहीं हुआ (पेमेंट/बैंक साइट)"
                    else -> "नहीं हुआ ($res)"
                }
                history.add("$step. $action${if (id >= 0) " [$id]" else ""}${if (text.isNotBlank()) " \"${text.take(40)}\"" else ""} → $note")
                if (action == "type" || action == "select" || action == "scroll_down" || action == "scroll_up") delay(400)
                else waitLoaded()
            }
            return err("काफ़ी कोशिश के बाद भी काम पूरा नहीं हुआ। ब्राउज़र में जहाँ रुका है वहाँ से आप खुद देख लें।")
        } finally {
            withContext(Dispatchers.Main) { b.setStatus(null) }
        }
    }

    private fun parse(t: String): JSONObject? {
        val s = t.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(s) } catch (e: Exception) {
            val i = s.indexOf('{'); val j = s.lastIndexOf('}')
            if (i >= 0 && j > i) try { JSONObject(s.substring(i, j + 1)) } catch (e2: Exception) { null } else null
        }
    }

    private fun err(msg: String) = JSONObject().put("status", "error").put("message", msg)
}
