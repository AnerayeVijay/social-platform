package com.example.social.web

import com.example.social.domain.Post

private val messages = linkedMapOf(
    "label" to "Images (optional): up to 4 JPEG or PNG files, 5 MB each",
    "remove" to "Remove image",
    "selected" to "Selected image",
    "tooMany" to "You can attach at most 4 images.",
    "invalid" to "Each image must be a JPEG or PNG file of at most 5 MB.",
    "empty" to "Write something or add at least one image.",
    "publishing" to "Publishing…",
    "400" to "Your post could not be published. Check that the images are valid JPEG or PNG files and the text is at most 2000 characters.",
    "401" to "Please sign in again to publish.",
    "403" to "Your session has expired. Reload the page and try again.",
    "408" to "The upload took too long. Please try again.",
    "413" to "The images are too large. Use at most 4 images of 5 MB each.",
    "429" to "Image processing is busy. Please try again shortly.",
    "other" to "Your post could not be published. Please try again."
)

/**
 * Image picker for the post composer. Without JavaScript the form stays a plain text post; the script adds the
 * picker, previews and a multipart submit that is used only when images are selected. Interface copy lives in
 * hidden elements so the page translation pass reaches it, and file names are only ever set with textContent.
 */
internal fun composerImages(): String {
    val copy = messages.entries.joinToString("") { "<span data-msg=\"${it.key}\">${escapeHtml(it.value)}</span>" }
    return "<div class=\"image-picker\" hidden><div class=\"image-messages\" hidden>$copy</div>" +
        "<label for=\"post-images\">${escapeHtml(messages.getValue("label"))}</label>" +
        "<input id=\"post-images\" type=\"file\" accept=\"image/jpeg,image/png\" multiple>" +
        "<div class=\"image-previews\"></div><p class=\"image-status\" role=\"status\" aria-live=\"polite\"></p></div>" +
        "<script>$imageScript</script>"
}

private val imageScript = """
(function(){
var form=document.getElementById('body').form,picker=form.querySelector('.image-picker'),input=picker.querySelector('input');
var previews=picker.querySelector('.image-previews'),status=picker.querySelector('.image-status'),body=document.getElementById('body');
var items=[],MAX=${Post.MAX_IMAGES},LIMIT=5*1024*1024;
function msg(key){var e=picker.querySelector('[data-msg="'+key+'"]');return e?e.textContent:key}
function say(key){status.textContent=key?msg(key):''}
function sync(){body.required=items.length===0}
function render(){
 previews.textContent='';
 items.forEach(function(item,index){
  var figure=document.createElement('figure'),img=document.createElement('img'),remove=document.createElement('button');
  img.src=item.url;img.alt=msg('selected')+' '+(index+1);
  remove.type='button';remove.className='remove-image';remove.textContent=msg('remove')+' '+(index+1);
  remove.addEventListener('click',function(){URL.revokeObjectURL(item.url);items.splice(items.indexOf(item),1);say('');sync();render()});
  figure.appendChild(img);figure.appendChild(remove);previews.appendChild(figure)
 });
 sync()
}
function busy(on){form.querySelector('button[type=submit]').disabled=on;input.disabled=on;previews.querySelectorAll('button').forEach(function(b){b.disabled=on})}
input.addEventListener('change',function(){
 var files=Array.prototype.slice.call(input.files);input.value='';say('');
 if(items.length+files.length>MAX){say('tooMany');return}
 if(files.some(function(f){return (f.type!=='image/jpeg'&&f.type!=='image/png')||f.size>LIMIT||f.size===0})){say('invalid');return}
 files.forEach(function(file){items.push({file:file,url:URL.createObjectURL(file)})});render()
});
window.addEventListener('pagehide',function(){items.forEach(function(i){URL.revokeObjectURL(i.url)})});
form.addEventListener('submit',function(event){
 if(items.length===0)return;
 event.preventDefault();
 var data=new FormData();
 ['body','visibility','font','size'].forEach(function(name){data.append(name,form.elements[name].value)});
 items.forEach(function(item){data.append('images',item.file,item.file.name)});
 busy(true);say('publishing');
 fetch(form.action,{method:'POST',body:data,credentials:'same-origin',headers:{'X-CSRF-Token':form.elements.csrf.value}}).then(function(response){
  if(response.ok&&response.redirected){items.forEach(function(i){URL.revokeObjectURL(i.url)});window.location.assign(response.url);return}
  busy(false);say(messages_for(response.status))
 }).catch(function(){busy(false);say('other')})
});
function messages_for(status){return picker.querySelector('[data-msg="'+status+'"]')?String(status):'other'}
picker.hidden=false;sync()
})();
"""
