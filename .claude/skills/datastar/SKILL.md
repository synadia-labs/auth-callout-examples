---
name: datastar
description: Best practices and guidance for building web applications with Datastar, a hypermedia framework. Use when creating reactive UIs with backend-driven state, SSE streaming, or data-* attribute-based interactivity. Triggers on: datastar, hypermedia ui, sse streaming, data-signals.
---

# Datastar Skill

Comprehensive reference for Datastar usage in the Insights web application.
Datastar is a hypermedia framework combining backend-driven reactivity (SSE)
with frontend declarative attributes (`data-*`). The Insights codebase uses
Datastar Pro with Rocket.

https://data-star.dev/

## Core Concepts

- **Backend-driven SSE**: Server sends HTML fragments and signal patches via
  Server-Sent Events
- **`data-*` attributes**: Declare behavior declaratively on HTML elements -- no
  client-side framework needed
- **SSE event types**: `datastar-merge-fragments` (DOM morphing),
  `datastar-merge-signals` (reactive data), `datastar-remove-fragments`,
  `datastar-remove-signals`, `datastar-execute-script`

## Core Attributes

| Attribute            | Purpose                      | Modifiers / Key Details                                                                                        |
| -------------------- | ---------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `data-signals`       | Declare/patch signals        | `__ifmissing` for defaults; `_` prefix = private (excluded from requests); dot-notation nesting: `data-signals:foo.bar="1"` |
| `data-bind`          | Two-way binding              | Works with input/select/textarea; preserves types; hyphenated names convert to camelCase                       |
| `data-on`            | Event listeners              | `__debounce`, `__throttle`, `__once`, `__outside`, `__window`, `__prevent`, `__stop`, `__passive`, `__capture` |
| `data-show`          | Conditional visibility       | **Must** add `style="display: none"` to prevent flicker before Datastar initializes                            |
| `data-class`         | Conditional classes          | Object syntax `{'class': $signal}` or named `data-class:name="expr"`                                           |
| `data-text`          | Bind text content            | Auto-updates on signal change                                                                                  |
| `data-attr`          | Bind any attribute           | Object syntax or named `data-attr:name="expr"`                                                                 |
| `data-computed`      | Derived signals              | Read-only; must not perform side effects                                                                       |
| `data-ref`           | DOM element reference        | Creates signal pointing to element                                                                             |
| `data-indicator`     | Fetch status tracking        | Named: `data-indicator:fetching`; boolean signal true while fetching; define before fetch in `data-init`       |
| `data-init`          | Run on initialization        | `__delay` and `__viewtransition` modifiers                                                                     |
| `data-effect`        | Side effects                 | Runs on load + whenever dependencies change                                                                    |
| `data-ignore`        | Skip Datastar processing     | `__self` modifier to only skip element, not children                                                           |
| `data-ignore-morph`  | Skip morphing                | Essential for elements managed by external libraries (ECharts)                                                 |
| `data-preserve-attr` | Keep attributes during morph | Space-separated list of attribute names                                                                        |
| `data-on-intersect`  | Viewport visibility          | `__once`, `__exit`, `__half`, `__full`, `__threshold` modifiers                                                |
| `data-on-interval`   | Timed execution              | Default 1s; `__duration` modifier to customize                                                                 |

## Pro Attributes

| Attribute               | Purpose               | Key Details                                             |
| ----------------------- | --------------------- | ------------------------------------------------------- |
| `data-persist`          | Local/session storage | `__session` modifier; filter with include/exclude regex |
| `data-replace-url`      | URL without reload    | Expression-evaluated template literal                   |
| `data-custom-validity`  | Form validation       | Return empty string for valid, message for invalid      |
| `data-scroll-into-view` | Auto-scroll           | Modifiers for behavior, alignment, focus                |

## Action Plugins

| Action                   | Purpose                  | Key Details                           |
| ------------------------ | ------------------------ | ------------------------------------- |
| `@get(url, opts)`        | GET request              | Signals as query params; SSE response |
| `@post(url, opts)`       | POST request             | Signals in JSON body                  |
| `@put(url, opts)`        | PUT request              | Same pattern as @post                 |
| `@patch(url, opts)`      | PATCH request            | Same pattern as @post                 |
| `@delete(url, opts)`     | DELETE request           | Same pattern as @post                 |
| `@setAll(value, filter)` | Bulk signal update       | Filter with include/exclude regex     |
| `@toggleAll(filter)`     | Bulk boolean toggle      | Same filter pattern                   |
| `@peek(fn)`              | Read without subscribing | Prevents reactivity triggers          |
| `@clipboard(text)`       | Copy to clipboard        | Pro; optional base64                  |
| `@fit(v, ...)`           | Linear interpolation     | Pro; value range mapping              |
| `@intl(...)`             | Locale-aware formatting  | Pro; dates, numbers, currencies, relative time |

## Action Options (for @get/@post etc.)

| Option                | Default                                  | Purpose                                 |
| --------------------- | ---------------------------------------- | --------------------------------------- |
| `contentType`         | `'json'`                                 | `'json'` or `'form'`                    |
| `filterSignals`       | `{include: /.*/, exclude: /(^_\|._).*/}` | Control which signals are sent          |
| `selector`            | `null`                                   | Target specific form                    |
| `headers`             | `{}`                                     | Custom HTTP headers                     |
| `openWhenHidden`      | varies                                   | Keep SSE open when tab hidden           |
| `retry`               | `'auto'`                                 | `'auto'`/`'error'`/`'always'`/`'never'` |
| `retryInterval`       | `1000`                                   | Initial retry delay (ms)                |
| `retryScaler`         | `2`                                      | Exponential backoff multiplier          |
| `retryMaxWaitMs`      | `30000`                                  | Max retry interval                      |
| `retryMaxCount`       | `10`                                     | Max retry attempts                      |
| `requestCancellation` | `'auto'`                                 | `'auto'`/`'cleanup'`/`'disabled'`/AbortController |

## SSE Event Types

**`datastar-merge-fragments`** -- Morphs DOM fragments

- Parameters: `selector`, `mode`, `namespace`, `useViewTransition`
- Mode values: `morph` **(default)**, `inner`, `outer`, `prepend`, `append`,
  `before`, `after`, `upsertAttributes`
- Default `morph` diffs and patches only changed nodes (most efficient)
- Top-level elements require `id` attributes for morphing

**`datastar-merge-signals`** -- Updates reactive signals

- Parameters: `signals` (JS object), `onlyIfMissing`
- Set signal to `null` to remove it

**`datastar-remove-fragments`** -- Removes DOM elements by CSS selector

**`datastar-remove-signals`** -- Removes signals by path

**`datastar-execute-script`** -- Runs JS in the browser (auto-removed after execution)

## Go SDK (`github.com/starfederation/datastar/sdk/go`)

Requires Go 1.24+.

**Constructor & signal reading:**
```go
sse := datastar.NewSSE(w, r)         // create SSE generator
datastar.ReadSignals(r, &signals)    // parse incoming signals from request
```

**Fragment methods:**
```go
sse.MergeFragments(fragment string, opts ...MergeFragmentOption) error
sse.RemoveFragments(selector string, opts ...RemoveFragmentsOption) error
```

**Signal methods:**
```go
sse.MergeSignals(signalsJSON []byte, opts ...MergeSignalsOption) error
sse.MarshalAndMergeSignals(signals any, opts ...MergeSignalsOption) error
sse.MarshalAndMergeSignalsIfMissing(signals any, opts ...MergeSignalsOption) error
sse.RemoveSignals(paths ...string) error
```

**Script / navigation methods:**
```go
sse.ExecuteScript(script string, opts ...ExecuteScriptOption) error
sse.ConsoleLog(msg string) error
sse.ConsoleError(err error) error
sse.Redirect(url string) error
sse.ReplaceURL(u url.URL) error
sse.ReplaceURLQuerystring(r *http.Request, values url.Values) error
sse.Prefetch(urls ...string) error
sse.DispatchCustomEvent(eventName string, detail any) error
```

**`MergeFragments` options:**
```go
WithMergeMode(FragmentMergeMode)   // morph | inner | outer | prepend | append | before | after | upsertAttributes
WithSelector(selector string)      // CSS selector; defaults to top-level element id
WithSelectorID(id string)          // shorthand: "#id"
WithSettleDuration(d time.Duration) // default 300ms
WithUseViewTransitions()
```

**`MergeSignals` options:**
```go
WithOnlyIfMissing(bool)  // skip signals already set on client
```

**Default constants:**
```go
DefaultFragmentsSettleDuration = 300 * time.Millisecond
DefaultSseRetryDuration        = 1000 * time.Millisecond
```

## Rocket Web Components

Datastar Pro's web component system, used for ECharts integration.

**Definition**: `<template data-rocket:component-name>` with `<script>` setup
block

**Signal scoping**:

- `$$signal` = component-scoped (auto-namespaced, auto-cleaned on removal)
- `$signal` = global (shared across all components)

**Props**: `data-props:name="type|transforms|validations|=default"`

- Types: `string`, `int`, `float`, `bool`, `js` (evaluated expression)
- Passed via `data-attr:name="expr"` on the component element

**Imports**: `data-import:name="url"` loads ESM modules; `__iife` modifier for
UMD/IIFE

**Reactivity**:

- `effect(() => { ... })` -- runs on dependency change (side effects)
- `computed(() => expr)` -- derived reactive value
- `onCleanup(() => { ... })` -- teardown when component removed

**DOM refs**: `data-ref="name"` creates `$$name` signal pointing to element

**Lifecycle**: Template registers -> instance created on DOM insert -> setup
scripts run -> effects track signals -> `onCleanup` on removal

### Signal Initialization (SignalBuilder)

Use in templates as: `data-signals__ifmissing={ sig }`

### Anti-Flicker Pattern

Elements using `data-show` must include `style="display: none"`:

```templ
// Correct
<div style="display: none" data-show="$searchopen">

// Wrong -- flashes visible before Datastar hides it
<div data-show="$searchopen">
```

## The Tao of Datastar

1. **State in the Right Place** -- Backend is the source of truth
2. **Start with the Defaults** -- Question before customizing
3. **Patch Elements & Signals** -- Backend actively drives frontend
4. **Use Signals Sparingly** -- Reserve for user interactions and form binding
5. **In Morph We Trust** -- Send large DOM trees; morphing diffs efficiently
6. **SSE Responses** -- Stream 0-to-n patches via text/event-stream
7. **Compression** -- Compress SSE with Brotli for large DOM chunks
8. **Backend Templating** -- Use Templ to stay DRY
9. **Page Navigation** -- Use anchors and redirects
10. **Browser History** -- Let browsers manage history
11. **CQRS** -- Separate reads (long-lived SSE) from writes (short-lived POST)
12. **Loading Indicators** -- Use data-indicator for fetch status
13. **No Optimistic Updates** -- Never update UI before backend confirms
14. **Accessibility** -- Semantic HTML, ARIA attributes, keyboard support
