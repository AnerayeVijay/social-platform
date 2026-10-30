package com.example.social.web

internal val hashtagSuggestScript = """
(function(){
var TOKEN=/(^|[^\p{L}\p{M}\p{N}_\/&@#.:])#([\p{L}\p{M}\p{N}_]{0,50})${'$'}/u;
document.querySelectorAll('[data-hashtag-suggest]').forEach(function(input){
var mode=input.getAttribute('data-hashtag-suggest');
var list=document.createElement('ul');
list.className='hashtag-suggestions';list.hidden=true;list.setAttribute('role','listbox');
(mode==='search'&&input.form?input.form:input).insertAdjacentElement('afterend',list);
var sequence=0,timer=null;
function current(){
if(mode==='search')return{query:input.value.trim().replace(/^#/,''),start:0,end:input.value.length};
var before=input.value.slice(0,input.selectionStart);
var match=TOKEN.exec(before);
if(!match)return null;
return{query:match[2],start:before.length-match[2].length-1,end:before.length};
}
function render(items){
list.textContent='';
items.forEach(function(item){
var option=document.createElement('li');
var button=document.createElement('button');
button.type='button';
button.textContent='#'+item.name;
var count=document.createElement('span');
count.textContent=item.posts+(item.posts===1?' post':' posts');
button.appendChild(count);
button.addEventListener('mousedown',function(event){event.preventDefault();pick(item.name);});
option.appendChild(button);
list.appendChild(option);
});
list.hidden=items.length===0;
}
function pick(name){
if(mode==='search'){window.location.href='/hashtags/'+encodeURIComponent(name);return;}
var token=current();
if(!token)return;
var value=input.value;
input.value=value.slice(0,token.start)+'#'+name+' '+value.slice(token.end);
var caret=token.start+name.length+2;
input.setSelectionRange(caret,caret);
list.hidden=true;
input.focus();
}
function refresh(){
var token=current();
if(!token||!token.query){list.hidden=true;return;}
var request=++sequence;
fetch('/api/hashtags/search',{method:'POST',credentials:'same-origin',headers:{'content-type':'application/json'},body:JSON.stringify({query:token.query,limit:5})})
.then(function(response){return response.ok?response.json():{hashtags:[]};})
.then(function(data){if(request===sequence)render(data.hashtags||[]);})
.catch(function(){list.hidden=true;});
}
input.addEventListener('input',function(){clearTimeout(timer);timer=setTimeout(refresh,150);});
input.addEventListener('blur',function(){setTimeout(function(){list.hidden=true;},150);});
input.addEventListener('keydown',function(event){if(event.key==='Escape')list.hidden=true;});
});
})();
""".trimIndent()
