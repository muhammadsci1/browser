# Dev Debug Browser

A specialized Android WebView browser for local web application testing and professional development workflows.

## Core features

- Chrome-like browser shell with address bar, progress indicator, back/forward/reload, tab menu, history, and developer settings.
- Android WebView rendering with JavaScript, cookies, DOM storage, service worker settings, multi-window support, zoom support, and safe browsing.
- Room-backed local browsing history database.
- Foreground keep-alive service with persistent notification and **Stop** action to reduce background process termination during long-running test sessions.
- Tab state persistence through Activity state bundles plus URL/title session restore after process recreation.
- Modern Kotlin + AndroidX + Gradle Kotlin DSL project structure.
- Adaptive minimalist launcher icon.
- GitHub Actions workflow at `.github/workflows/android.yml` builds `assembleDebug` and uploads the debug APK artifact.

## Developer Mode

Developer Mode is **OFF by default**. Open **⋮ → Developer Settings** to enable it manually.

When enabled, debug behavior is scoped to the exact **Trusted Origin** you configure, for example:

```text
http://192.168.1.50:3000
http://localhost:5173
file://
```

Developer Mode includes:

- `mixedContentMode` selection for the trusted top-level page.
- `allowUniversalAccessFromFileURLs` for explicitly trusted `file://` pages.
- Persistent visual indicator while Developer Mode is enabled.
- `shouldInterceptRequest` CORS header rewriting while the current top-level page matches the Trusted Origin.

### CORS header rewriter behavior

For trusted-origin sessions only, the WebViewClient intercepts HTTP/HTTPS `GET`, `HEAD`, and CORS `OPTIONS` requests, proxies the response, removes any existing `Access-Control-Allow-Origin` header, and injects:

```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD
Access-Control-Allow-Headers: *
Access-Control-Expose-Headers: *
Access-Control-Max-Age: 86400
```

Android's `WebResourceRequest` does not expose request bodies, so POST/PUT/PATCH request bodies cannot be safely replayed from `shouldInterceptRequest`; those requests are allowed to proceed normally.

## Build locally

Install Android Studio or a compatible Android SDK/JDK 17 environment, then run:

```bash
gradle assembleDebug
```

The GitHub Actions workflow installs Gradle 8.10.2 and Android SDK platform 35 automatically.
