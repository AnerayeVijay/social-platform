package com.example.social.web

private val darkPalette = """
    color-scheme:dark;--page-bg:#101916;--ink:#e3eee8;--muted:#adbbb2;--surface:#19251f;
    --line:#34473b;--soft:#23382c;--teal:#a5b4fc;--field:#142119;--button-ink:#10231a;
    --page-overlay:rgba(8,16,12,.55);--header-overlay:rgba(16,25,22,.8);--shadow:0 2px 8px #0005;
""".trimIndent()

/** Supplemental tokens leave the existing responsive layout intact. */
internal val appearanceStyles = """
    :root{--page-bg:#f3f6f5;--ink:#202e2b;--field:#fafcfb;--button-ink:#fff;--page-overlay:rgba(243,246,245,.35);--header-overlay:rgba(255,255,255,.8);background:var(--page-bg);color:var(--ink)}
    :root[data-theme=dark]{$darkPalette}
    @media(prefers-color-scheme:dark){:root[data-theme=system]{$darkPalette}}
    body{min-height:100vh;color:var(--ink)}
    body.custom-background{background-image:linear-gradient(var(--page-overlay),var(--page-overlay)),var(--member-background);background-size:cover;background-position:center;background-attachment:fixed}
    .topbar.custom-header{background-image:linear-gradient(var(--header-overlay),var(--header-overlay)),var(--member-header);background-size:cover;background-position:center}
    h1,h2,h3,.post-body,.post-header strong,.comment>strong,.brand,.account-controls a,.settings-link,.sidebar-card strong,.member-result h2 a{color:var(--ink)}
    .topbar,.mobile-nav,.card,.empty,.create-post>summary,.skip:focus{background:var(--surface)}
    .sidebar-card,.avatar,.nav-link:hover,.nav-link[aria-current=page],.mobile-nav a[aria-current=page],.post-audience,.tags a,.pending-count,.followers-count,.following-count,.create-post>summary>span:first-child{background:var(--soft);color:var(--teal);border-color:var(--line)}
    .avatar,.post:nth-of-type(3n) .avatar{background:var(--soft);color:var(--teal)}
    .avatar img{display:block;width:100%;height:100%;object-fit:cover;border-radius:inherit}
    .profile-avatar{border-color:var(--surface)!important}.profile-intro{border-top-color:var(--soft)}
    .profile-bio{white-space:pre-wrap;overflow-wrap:anywhere;color:var(--ink)}
    input,textarea,select,.composer textarea,.language-selector select{background:var(--field);color:var(--ink);border-color:var(--line)}
    label,.brand-tagline,.aside-note,.eyebrow,.site-footer,.nav-link,.mobile-nav a,.follow-navigation a,.post-footer,.post-footer a{color:var(--muted)}
    .button,button{color:var(--button-ink)}
    .button:hover,button:hover{background:var(--soft);border-color:var(--teal);color:var(--teal)}
    .feed-tabs .button{color:var(--muted)}.feed-tabs .button[aria-current=page]{color:var(--teal)}
    .reply-composer summary,.original-text summary{color:var(--teal)}
    .account-controls button,.language-selector button,.post>form .button,.comment>form .button,.post>form .button[aria-pressed=true],.comment>form .button[aria-pressed=true],.card:has(form[action="/login"]) form>p:first-child .button{background:var(--soft);color:var(--teal);border-color:var(--line)}
    .create-post>summary,.create-post[open]>summary{color:var(--ink);border-color:var(--line)}
    .create-post[open]>summary,.empty:before{background:var(--soft)}
    .settings-link{font-size:.8rem;font-weight:650;min-height:36px;display:inline-flex;align-items:center}
    .image-preview{display:block;max-width:100%;height:auto;max-height:180px;border-radius:12px;margin:1rem 0}.avatar-preview{width:96px;height:96px;object-fit:cover;border-radius:50%}
    .image-upload{margin-top:1rem}.image-upload button{margin-top:.7rem}.upload-message{overflow-wrap:anywhere}
    @media(max-width:700px){body.custom-background{background-attachment:scroll}}
""".trimIndent()
