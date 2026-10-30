package com.example.social.web

/** Uses the member's theme variables so light, dark and system themes all apply. */
internal val notificationStyles = """
    .notification-bell{position:relative;display:inline-block}
    .notification-bell>summary{list-style:none;cursor:pointer;display:inline-flex;align-items:center;position:relative;min-width:40px;min-height:40px;justify-content:center;border-radius:999px;color:var(--ink)}
    .notification-bell>summary::-webkit-details-marker{display:none}
    .notification-bell>summary:focus-visible{outline:2px solid var(--teal);outline-offset:2px}
    .notification-bell svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
    .notification-badge{position:absolute;top:0;right:0;min-width:18px;height:18px;padding:0 4px;box-sizing:border-box;border-radius:9px;background:var(--teal);color:var(--surface);font-size:.7rem;font-weight:700;line-height:18px;text-align:center}
    .notification-badge[hidden],.notification-empty[hidden]{display:none}
    .notification-panel{position:absolute;right:0;top:calc(100% + .4rem);z-index:30;box-sizing:border-box;width:min(22rem,calc(100vw - 1.5rem));max-height:70vh;overflow-y:auto;background:var(--surface);color:var(--ink);border:1px solid var(--line);border-radius:14px;padding:.6rem;box-shadow:var(--shadow)}
    .notification-list{list-style:none;margin:0;padding:0}
    .notification-item form{margin:0}
    .notification-item button{width:100%;text-align:left;background:transparent;color:var(--ink);border:0;border-radius:10px;padding:.55rem .6rem;white-space:normal;overflow-wrap:anywhere;font-weight:400}
    .notification-item button:hover,.notification-item button:focus-visible{background:var(--soft);color:var(--ink)}
    .notification-item.unread button{font-weight:650;background:var(--soft)}
    .notification-item.read button{color:var(--muted)}
    .notification-panel form.notification-read-all{margin:.4rem 0}
    .notification-panel a{color:var(--teal)}
    .notification.unread{border-left:4px solid var(--teal)}.notification.read{opacity:.8}
    .notification button{text-align:left;white-space:normal;overflow-wrap:anywhere}
    .notification.card{border-radius:0;border-width:0 0 1px;box-shadow:none;margin:0;padding:0}
    .notification.card button.button{width:100%;justify-content:flex-start;min-height:0;background:transparent;color:var(--ink);border:0;border-radius:0;box-shadow:none;padding:1rem 1.25rem;font-weight:400}
    .notification.card button.button:hover{background:var(--soft);color:var(--ink)}
    .notification.card.unread{border-left-width:4px}.notification.card.unread button.button{font-weight:650}
    .notification.card time{display:block;padding:0 1.25rem 1rem;font-size:.75rem;color:var(--muted)}
    @media(max-width:700px){.notification-bell{position:static}.notification-panel{left:.75rem;right:.75rem;width:auto;top:100%}}
""".trimIndent()
