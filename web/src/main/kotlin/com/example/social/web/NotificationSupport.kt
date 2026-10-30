package com.example.social.web

import com.example.social.application.Session
import com.example.social.http.AccessControl
import com.example.social.notifications.Notification
import com.example.social.notifications.NotificationKind
import com.example.social.notifications.Notifications
import org.http4k.core.HttpHandler
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode

private const val RECENT_COUNT = 10

private const val BELL_ICON = "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9\"/><path d=\"M13.7 21a2 2 0 0 1-3.4 0\"/></svg>"

private const val POLL_SCRIPT = """(function(){
var bell=document.querySelector('.notification-bell');if(!bell)return;
var badge=bell.querySelector('.notification-badge'),list=bell.querySelector('.notification-list'),empty=bell.querySelector('.notification-empty');
var csrf=bell.querySelector('input[name=csrf]').value,verbs={},busy=false,stopped=false,timer=null;
bell.querySelectorAll('[data-kind]').forEach(function(e){verbs[e.getAttribute('data-kind')]=e.textContent;});
function item(n){
if(!Number.isSafeInteger(n.id)||n.id<=0||typeof verbs[n.kind]!=='string')return null;
var li=document.createElement('li');li.className='notification-item '+(n.read?'read':'unread');
var form=document.createElement('form');form.method='post';form.action='/notifications/'+n.id+'/open';
var token=document.createElement('input');token.type='hidden';token.name='csrf';token.value=csrf;
var button=document.createElement('button');button.type='submit';
var actor=document.createElement('strong');actor.className='notification-actor';actor.setAttribute('translate','no');actor.textContent=String(n.actorName);
var verb=document.createElement('span');verb.className='notification-verb';verb.textContent=verbs[n.kind];
button.append(actor,' ',verb);form.append(token,button);li.append(form);return li;}
function render(data){
var count=Number(data.unreadCount)||0;badge.textContent=count>99?'99+':String(count);badge.hidden=count<1;
if(list.contains(document.activeElement))return;
var items=(data.notifications||[]).map(item).filter(Boolean);
list.replaceChildren.apply(list,items);empty.hidden=items.length>0;}
function stop(){stopped=true;clearInterval(timer);badge.hidden=true;list.replaceChildren();empty.hidden=false;}
function poll(){
if(busy||stopped||document.hidden)return;busy=true;
fetch('/notifications/recent',{credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}})
.then(function(r){if(r.status===401||r.status===403){stop();return null;}if(!r.ok)throw new Error('retry');return r.json();})
.then(function(data){if(data)render(data);}).catch(function(){}).then(function(){busy=false;});}
timer=setInterval(poll,30000);
document.addEventListener('visibilitychange',function(){if(!document.hidden)poll();});
})();"""

private fun item(notification: Notification, csrf: String) =
    "<li class=\"notification-item ${if (notification.read) "read" else "unread"}\"><form action=\"/notifications/${notification.id}/open\" method=\"post\">$csrf" +
        "<button type=\"submit\"><strong class=\"notification-actor\" translate=\"no\">${escapeHtml(notification.actorName)}</strong> <span class=\"notification-verb\">${notificationVerb(notification.kind)}</span></button></form></li>"

private fun bell(notifications: Notifications, session: Session): String {
    val csrf = "<input type=\"hidden\" name=\"csrf\" value=\"${escapeHtml(session.csrf)}\">"
    val recent = notifications.list(session.accountId, RECENT_COUNT)
    val unread = notifications.unreadCount(session.accountId)
    val verbs = NotificationKind.entries.joinToString("") { "<span data-kind=\"${it.name}\">${notificationVerb(it)}</span>" }
    return "<details class=\"notification-bell\"><summary aria-label=\"Notifications\">$BELL_ICON<span class=\"notification-badge\"${if (unread == 0L) " hidden" else ""}>${if (unread > 99) "99+" else unread}</span></summary>" +
        "<div class=\"notification-panel\"><ul class=\"notification-list\">${recent.joinToString("") { item(it, csrf) }}</ul>" +
        "<p class=\"notification-empty\"${if (recent.isEmpty()) "" else " hidden"}>No notifications yet.</p>" +
        "<form class=\"notification-read-all\" action=\"/notifications/read-all\" method=\"post\">$csrf<button type=\"submit\">Mark all as read</button></form>" +
        "<a href=\"/notifications\">View all notifications</a><span hidden>$verbs</span></div></details><script>$POLL_SCRIPT</script>"
}

/** Adds the signed-in bell to full HTML pages only; JSON, media and signed-out pages pass through untouched. */
fun notificationSupport(next: HttpHandler, notifications: Notifications, access: AccessControl): HttpHandler = { request ->
    val response = next(request)
    val session = if (response.header("content-type")?.startsWith("text/html") == true) access.session(request) else null
    if (session == null) response
    else {
        val document = Jsoup.parse(withAccountHeader(response.bodyString(), session, request.uri.path))
        document.outputSettings().prettyPrint(false)
        document.head().appendElement("style").attr("id", "notification-styles").appendChild(DataNode(notificationStyles))
        val container = document.selectFirst(".account-controls") ?: document.selectFirst(".topbar-inner") ?: document.body()
        container.prepend(bell(notifications, session))
        response.body(document.outerHtml())
    }
}
