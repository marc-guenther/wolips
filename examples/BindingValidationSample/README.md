# Binding Validation Sample

A small WebObjects application whose components deliberately exercise **every
special case** in WOLips' component binding validation. It exists to drive and
verify the validation-performance work: open the components in Eclipse (with the
`ValidationProfiler` enabled) and you get representative, repeatable numbers, and
the expected problem markers double as a correctness check.

> This is **not** a Maven/Tycho module — it is plain sample sources, not part of
> the plugin build. Import it as a standalone project into an Eclipse workspace
> that has WOLips installed.

## Importing

1. Ensure WebObjects + Project Wonder frameworks are available to WOLips (the
   `.classpath` references the usual `WOFramework/...` containers: `ERExtensions`,
   `JavaWebObjects`, `JavaFoundation`, `JavaEOControl`, `WOOgnl`, …).
2. *File ▸ Import ▸ Existing Projects into Workspace* and select this directory.
3. Let it build so JDT can resolve the model and component types. The binding
   validator resolves keypaths against these types, so a clean build is required
   for the markers below to appear.

The component templates resolve their keypaths against the small model in
`Sources/com/example/validationsample/model/`:

| Type | Purpose |
| --- | --- |
| `Person` | Plain (non-KVC) object → unknown keys are **hard errors**. Mix of String/primitive/boolean/collection/component getters, a settable property, a gettable-only property, and a `@Deprecated` method. |
| `Address` | Plain navigable object used for nested keypaths. |
| `LegacyKvcObject` | Implements `NSKeyValueCoding` → unknown keys become **"unable to verify"** warnings. |
| `Repository<T>` | Generic base: `currentObject()` returns `T`, `allObjects()` returns `NSArray<T>`. |
| `PersonRepository` / `AddressRepository` | Bind `T` to `Person` / `Address` so the *same* generic member resolves to *different* concrete types by context — the case fix #2 makes cacheable. |

## What each component covers

| Component | Validation cases exercised |
| --- | --- |
| **Main** | valid simple keypath (`person.name`), primitive leaf (`person.age`), boolean via `is` prefix (`person.active`); literal / number / `null` values; **unused .wod element** (`LiteralBinding`, `NumberBinding`, `NullBinding` aren't in the HTML); **HTML element not defined in .wod** (`MissingFromWod`). |
| **ValidKeyPaths** | nested valid keypaths (`person.address.street`), chained navigation (`person.bestFriend.name`), a **settable** binding (`person.nickname`), a **gettable-but-not-settable** binding (`person.computedValue`). |
| **InvalidKeyPaths** | invalid leaf key on a plain type (`person.bogus` → *no key* error), invalid key **mid-keypath** (`person.address.bogus`), unknown key on the component itself (`totallyUnknownKey` → resolves through the component, a `WOComponent`). |
| **CollectionKeyPaths** | binding through an `NSArray` (**collection passthrough** warning: `person.friends.name`), valid array **`@operator`s** (`@count`, `@avg`), **unknown operator** (`@bogusOperator`), and the **`var:` value namespace** for a repetition item. |
| **KvcAndComponentKeyPaths** | `NSKeyValueCoding` type (`legacyObject.anyDynamicKey`), a concrete key that still validates on a KVC type (`legacyObject.title`), and **passing through a `WOComponent`** (`person.relatedComponent.*`, `someComponent.*`). |
| **HelperFunctions** | **helper-function** syntax (`person.name\|uppercaseString` → *unable to verify helper function*). |
| **OgnlBindings** | unquoted **OGNL** (`~person.age > 18`), quoted OGNL (`"~person.name"`), and OGNL referencing an **invalid identifier** (`~person.bogusOgnlKey`). |
| **DeprecatedComponent** | a `@Deprecated` reusable component (target of the case below). |
| **DeprecatedBindings** | binding to a **deprecated member** (`person.oldName`), and binding a **deprecated component** (`DeprecatedComponent`). |
| **GenericKeyPaths** | the generics case: `personRepository.currentObject.name` (T→`Person`), `addressRepository.currentObject.city` (T→`Address`), a deeper chain, an `NSArray<T>` passthrough, and an invalid key on the resolved generic type. Both valid generic paths **must resolve without errors** — that's the correctness canary for fix #2's copy-on-retrieve caching. |
| **ApiValidated** (+ `.api`) | a reusable component declaring a **required** binding (`value`) and an **`<and><unbound/></and>` validation** rule. |
| **UsesApiValidated** | binds `ApiValidated` three ways: valid; **missing a required binding**; and **failing the `.api` validation** rule. |
| **InlineBindings** | **inline `<wo:…>` binding** syntax (no `.wod`), the **`var:` namespace** for a repetition item, and an invalid inline key. |
| **StructuralProblems** | **duplicate binding** name in one element; **caret** (`^inheritedValue`) passthrough binding; `<webobject>` tag **missing `name`**; `<webobject>` tag with an **attribute other than `name`**. |

Components that should validate **cleanly** (no markers): the bound parts of
`Main`, all of `ValidKeyPaths`, `ApiValidated`, and the valid paths in
`GenericKeyPaths` (`PersonName`, `AddressCity`, `DeepGeneric`, `GenericCollection`).
Everything else intentionally produces markers — useful for confirming behavior
is unchanged across the performance refactors.

## Profiling with this project

The validator is instrumented by `ValidationProfiler` (in the
`org.objectstyle.wolips.bindings` plugin). It is **off by default**.

1. Launch the Eclipse instance that hosts WOLips with
   `-Dwolips.validation.profile=true` (or call
   `ValidationProfiler.setEnabled(true)`).
2. Open / save a component (or trigger a clean build) to run a validation pass.
   A summary prints to `System.out` (the host Eclipse console) at the end of
   each pass — wall-clock plus per-hotspot counts and timings.

Suggested A/B procedure to verify a fix:

- **Cold pass**: restart (or change any validation preference to clear the
  caches), then validate a component for the first time.
- **Warm pass**: validate the same component again.

Capture both on `main` and on the perf branch and compare. The metrics map to
the fixes:

- `BindingValueKeyPath.construct` — fix #1 (resolve keypath once per binding).
- `accessorKeys.hit` / `accessorKeys.miss`, `mutatorKeys.*`, `getBindingKeys` —
  fix #2 (generic-type key caching). `GenericKeyPaths` and `CollectionKeyPaths`
  are the components that move these the most.
- `preferenceStoreRead` / `preferenceCacheHit` — fix #4 (preference memoization).
- `resolveType` / `getTypeForName.*` — the underlying JDT type-resolution cost.

`GenericKeyPaths` is the most interesting target: before fix #2 every lookup on
the generic repositories missed the cache and re-ran the reflection scan; after
it, warm passes are almost all hits.

## Testing dependency-driven revalidation

The model classes are shared by many components, which makes this project a good
way to confirm that a Java change revalidates every component reachable through a
key path -- not just the component whose own class changed:

- Edit `model/Person.java` (e.g. rename or remove `name()`), then build. Every
  component that binds `person.*` should be revalidated and update its markers:
  `Main`, `ValidKeyPaths`, `InvalidKeyPaths`, `CollectionKeyPaths`,
  `KvcAndComponentKeyPaths`, `HelperFunctions`, `OgnlBindings`,
  `DeprecatedBindings`, `UsesApiValidated`, `InlineBindings`, and
  `StructuralProblems` -- even though none of *their* classes changed.
- Edit `model/Address.java` -- `ValidKeyPaths` (and any other component binding
  `*.address.*`) should revalidate.
- Edit `model/Person.java`'s superclass-supplied behavior or `Repository.java` --
  `GenericKeyPaths` should revalidate, since its key paths resolve through those
  types.

Components are only tracked once they have been validated at least since the last
full build, so do a full build (or open the components once) first; after that,
incremental Java edits drive the targeted revalidation.
