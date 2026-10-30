package com.example.social.web

import com.example.social.domain.Comment
import com.example.social.domain.PostId

/** Posts stored before media types were recorded only ever held JPEGs. */
internal const val LEGACY_MEDIA_TYPE = "image/jpeg"

private const val ACCEPTED_EXTENSIONS = ".jpg,.jpeg,.png,.apng,.webp,.gif,.heic,.avif,.mp4,.webm,.mov,.m4v,.mp3,.aac,.m4a,.wav,.ogg,.flac"

private val messages = linkedMapOf(
    "label" to "Attachments (optional): images, video or audio",
    "remove" to "Remove attachment",
    "tooMany" to "You can attach at most %n file(s).",
    "invalid" to "Unsupported file type, empty file or file too large (images 20 MB, video 100 MB, audio 25 MB).",
    "noPreview" to "No preview available for this file.",
    "publishing" to "Uploading…",
    "400" to "Could not send. Check the files are supported and the text is not too long.",
    "401" to "Please sign in again.",
    "403" to "Your session has expired. Reload the page and try again.",
    "408" to "The upload took too long. Please try again.",
    "413" to "The files are too large.",
    "429" to "Uploads are busy. Please try again shortly.",
    "other" to "Could not send. Please try again."
)

/**
 * Attachment picker for a composer form. Without JavaScript the form stays plain text (the picker is hidden);
 * the script adds the picker, previews and a multipart submit used only when files are selected. Interface copy
 * lives in hidden elements so the page translation pass reaches it, and file names are only set with textContent.
 */
internal fun mediaPicker(id: String, max: Int): String {
    val copy = messages.entries.joinToString("") { "<span data-msg=\"${it.key}\">${escapeHtml(it.value)}</span>" }
    return "<div class=\"media-picker image-picker\" data-max=\"$max\" hidden><div class=\"media-messages\" hidden>$copy</div>" +
        "<label for=\"$id\">${escapeHtml(messages.getValue("label"))}</label>" +
        "<input id=\"$id\" type=\"file\" accept=\"$ACCEPTED_EXTENSIONS\"${if (max > 1) " multiple" else ""}>" +
        "<div class=\"media-previews image-previews\"></div><p class=\"media-status image-status\" role=\"status\" aria-live=\"polite\"></p></div>"
}

private fun mediaKind(type: String) = when {
    type.startsWith("video/") -> "video"
    type.startsWith("audio/") -> "audio"
    else -> "image"
}

/** One attachment. The download link is always present so browsers that cannot play or show it still offer the file. */
internal fun mediaItem(url: String, type: String, imageAlt: String): String {
    val kind = mediaKind(type)
    val player = when (kind) {
        "video" -> "<video controls preload=\"metadata\" playsinline src=\"$url\"></video>"
        "audio" -> "<audio controls preload=\"metadata\" src=\"$url\"></audio>"
        else -> "<a href=\"$url\"><img src=\"$url\" alt=\"${escapeHtml(imageAlt)}\" loading=\"lazy\"></a>"
    }
    val fallback = "<span class=\"media-fallback\" hidden>${if (kind == "image") "This image could not be displayed." else "This $kind could not be played. Use the download link."}</span>"
    return "<figure class=\"media-item media-$kind\">$player$fallback<a class=\"media-download\" href=\"$url?download=1\" download>Download original $kind</a></figure>"
}

internal fun commentAttachment(post: PostId, comment: Comment): String = comment.attachmentType?.let {
    "<div class=\"comment-media\">" + mediaItem("/media/posts/${post.value}/comments/${comment.id.value}", it, "Comment image") + "</div>"
}.orEmpty()

internal const val mediaStyles = """
.media-item{margin:.6rem 0;max-width:100%}.media-item img,.media-item video{display:block;max-width:100%;height:auto;max-height:70vh;border-radius:10px}.media-item audio{display:block;width:100%;max-width:30rem}
.media-download{display:inline-block;margin-top:.3rem;font-size:.75rem}.media-fallback{font-size:.8rem;color:var(--muted)}.media-item.broken a:has(img),.media-item.broken video,.media-item.broken audio{display:none}.media-item.broken .media-fallback{display:block}
.post-gallery{display:grid;gap:.5rem;max-width:100%}.post-gallery[data-count="2"],.post-gallery[data-count="3"],.post-gallery[data-count="4"]{grid-template-columns:repeat(auto-fit,minmax(min(100%,14rem),1fr))}
.media-picker{margin:.6rem 0}.media-preview{margin:.5rem 0;max-width:100%}.media-preview img,.media-preview video{max-width:100%;max-height:16rem;border-radius:8px}.media-preview audio{width:100%;max-width:24rem}.media-name{display:block;font-size:.75rem;overflow-wrap:anywhere}
"""

/** A failed image is swapped for a message; capture phase because load errors do not bubble. */
internal const val mediaErrorScript = """
(function(){
function broken(el){var item=el.closest('.media-item');if(item)item.classList.add('broken')}
document.addEventListener('error',function(e){if(e.target&&/^(IMG|VIDEO|AUDIO)${'$'}/.test(e.target.tagName))broken(e.target)},true);
document.addEventListener('DOMContentLoaded',function(){document.querySelectorAll('.media-item img').forEach(function(i){if(i.complete&&i.naturalWidth===0)broken(i)})})
})();
"""

/** Initialises every picker on the page, including ones inserted later (loaded replies). Each picker keeps its own state. */
internal const val mediaPickerScript = """
(function(){
var MB=1048576,LIMITS={image:20*MB,video:100*MB,audio:25*MB};
var KINDS={jpg:'image',jpeg:'image',png:'image',apng:'image',webp:'image',gif:'image',heic:'image',avif:'image',mp4:'video',webm:'video',mov:'video',m4v:'video',mp3:'audio',aac:'audio',m4a:'audio',wav:'audio',ogg:'audio',flac:'audio'};
function kindOf(file){var m=/\.([^.]+)${'$'}/.exec(file.name);return m?KINDS[m[1].toLowerCase()]:undefined}
function init(picker){
 if(picker.dataset.ready)return;picker.dataset.ready='true';
 var form=picker.closest('form'),input=picker.querySelector('input[type=file]'),previews=picker.querySelector('.media-previews'),status=picker.querySelector('.media-status');
 var body=form.elements.body,max=parseInt(picker.dataset.max,10),items=[],submitting=false;
 function msg(key){var e=picker.querySelector('[data-msg="'+key+'"]');return e?e.textContent:key}
 function say(key){status.textContent=key?msg(key).replace('%n',max):''}
 function sync(){if(body)body.required=items.length===0}
 function revoke(item){URL.revokeObjectURL(item.url)}
 function busy(on){submitting=on;form.querySelectorAll('button').forEach(function(b){b.disabled=on});input.disabled=on}
 function preview(item,index){
  var wrap=document.createElement('div'),name=document.createElement('span'),remove=document.createElement('button'),el;
  wrap.className='media-preview';
  if(item.kind==='image'){el=document.createElement('img');el.alt=item.file.name;el.addEventListener('error',function(){el.replaceWith(Object.assign(document.createElement('span'),{textContent:msg('noPreview')}))})}
  else{el=document.createElement(item.kind);el.controls=true;el.preload='metadata'}
  el.src=item.url;
  name.className='media-name';name.textContent=item.file.name;
  remove.type='button';remove.className='remove-image';remove.textContent=msg('remove')+' '+(index+1);
  remove.addEventListener('click',function(){revoke(item);items.splice(items.indexOf(item),1);say('');render()});
  wrap.appendChild(el);wrap.appendChild(name);wrap.appendChild(remove);return wrap
 }
 function render(){previews.textContent='';items.forEach(function(item,i){previews.appendChild(preview(item,i))});sync()}
 input.addEventListener('change',function(){
  var files=Array.prototype.slice.call(input.files);input.value='';say('');
  if(items.length+files.length>max){say('tooMany');return}
  var kinds=files.map(kindOf);
  if(files.some(function(f,i){return !kinds[i]||f.size===0||f.size>LIMITS[kinds[i]]})){say('invalid');return}
  files.forEach(function(file,i){items.push({file:file,kind:kinds[i],url:URL.createObjectURL(file)})});render()
 });
 window.addEventListener('pagehide',function(event){if(!event.persisted)items.forEach(revoke)});
 form.addEventListener('submit',function(event){
  if(items.length===0)return;
  event.preventDefault();if(submitting)return;
  var data=new FormData();
  Array.prototype.forEach.call(form.elements,function(el){if(el.name&&el.name!=='csrf'&&el.type!=='file'&&el.type!=='submit'&&el.type!=='button')data.append(el.name,el.value)});
  items.forEach(function(item){data.append('attachments',item.file,item.file.name)});
  busy(true);say('publishing');
  fetch(form.action,{method:'POST',body:data,credentials:'same-origin',headers:{'X-CSRF-Token':form.elements.csrf.value}}).then(function(response){
   if(response.ok&&response.redirected){items.forEach(revoke);window.location.assign(response.url);return}
   busy(false);say(picker.querySelector('[data-msg="'+response.status+'"]')?String(response.status):'other')
  }).catch(function(){busy(false);say('other')})
 });
 picker.hidden=false;sync()
}
function scan(root){root.querySelectorAll('.media-picker').forEach(init)}
scan(document);
new MutationObserver(function(){scan(document)}).observe(document.body,{childList:true,subtree:true})
})();
"""
