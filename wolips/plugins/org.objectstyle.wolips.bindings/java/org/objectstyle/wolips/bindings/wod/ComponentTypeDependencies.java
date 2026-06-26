package org.objectstyle.wolips.bindings.wod;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.IType;

/**
 * Tracks, per component, the set of Java types its bindings' key paths resolve
 * through, so that a change to one of those types can trigger revalidation of
 * every component that depends on it -- not just the component whose own class
 * changed.
 *
 * <p>Recording is driven from the validation pass: the validator calls
 * {@link #beginRecording()} when a component's validation starts,
 * {@link TypeCache} calls {@link #recordType(IType)} as it resolves key paths,
 * and {@link #endRecording(String)} stores the collected type names against the
 * component when the pass ends. The builder queries the resulting reverse index
 * with {@link #componentsDependentOn(String)}.</p>
 *
 * <p>The index only knows about components validated since it was last cleared
 * (session start or a full build of their project). Components that have not yet
 * been validated are validated when first opened or on a full build, which
 * repopulates the index, so the gap is self-healing.</p>
 *
 * <p>Keys are component identifiers chosen by the validator -- in practice the
 * full workspace path of the {@code .wo} folder. Type identifiers are
 * fully-qualified Java type names.</p>
 */
public final class ComponentTypeDependencies {
  private static final ThreadLocal<Set<String>> RECORDING = new ThreadLocal<Set<String>>();

  /** componentKey -&gt; fully-qualified type names it depends on. */
  private static final Map<String, Set<String>> COMPONENT_TO_TYPES = new ConcurrentHashMap<String, Set<String>>();

  /** fully-qualified type name -&gt; componentKeys depending on it. */
  private static final Map<String, Set<String>> TYPE_TO_COMPONENTS = new ConcurrentHashMap<String, Set<String>>();

  private ComponentTypeDependencies() {
    // utility class
  }

  /** Starts collecting type dependencies on the current thread. */
  public static void beginRecording() {
    RECORDING.set(new HashSet<String>());
  }

  /** Records a type touched while resolving the component currently being validated. */
  public static void recordType(IType type) {
    if (type == null) {
      return;
    }
    Set<String> recording = RECORDING.get();
    if (recording != null) {
      recording.add(type.getFullyQualifiedName());
    }
  }

  /**
   * Ends recording on the current thread and stores the collected type names as
   * the dependency set of the given component. A null key discards the recording
   * (e.g. validation that is not tied to a locatable component).
   */
  public static void endRecording(String componentKey) {
    Set<String> recorded = RECORDING.get();
    RECORDING.remove();
    if (componentKey != null && recorded != null) {
      setDependencies(componentKey, recorded);
    }
  }

  private static synchronized void setDependencies(String componentKey, Set<String> typeNames) {
    Set<String> previous = COMPONENT_TO_TYPES.put(componentKey, typeNames);
    if (previous != null) {
      for (String oldType : previous) {
        if (!typeNames.contains(oldType)) {
          removeReverse(oldType, componentKey);
        }
      }
    }
    for (String typeName : typeNames) {
      Set<String> components = TYPE_TO_COMPONENTS.get(typeName);
      if (components == null) {
        components = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        TYPE_TO_COMPONENTS.put(typeName, components);
      }
      components.add(componentKey);
    }
  }

  private static void removeReverse(String typeName, String componentKey) {
    Set<String> components = TYPE_TO_COMPONENTS.get(typeName);
    if (components != null) {
      components.remove(componentKey);
      if (components.isEmpty()) {
        TYPE_TO_COMPONENTS.remove(typeName);
      }
    }
  }

  /** Returns a snapshot of the components whose key paths resolve through the given type. */
  public static Set<String> componentsDependentOn(String typeName) {
    Set<String> components = TYPE_TO_COMPONENTS.get(typeName);
    if (components == null) {
      return Collections.emptySet();
    }
    return new HashSet<String>(components);
  }

  /** Drops a component from the index (e.g. when its {@code .wo} folder is deleted). */
  public static synchronized void removeComponent(String componentKey) {
    Set<String> previous = COMPONENT_TO_TYPES.remove(componentKey);
    if (previous != null) {
      for (String typeName : previous) {
        removeReverse(typeName, componentKey);
      }
    }
  }

  /**
   * Clears every component whose key is within the given workspace path (a
   * project's full path), e.g. on a full build of that project.
   */
  public static synchronized void clearForProject(String projectFullPath) {
    if (projectFullPath == null) {
      return;
    }
    String prefix = projectFullPath.endsWith("/") ? projectFullPath : projectFullPath + "/";
    Set<String> componentKeys = new HashSet<String>(COMPONENT_TO_TYPES.keySet());
    for (String componentKey : componentKeys) {
      if (componentKey.equals(projectFullPath) || componentKey.startsWith(prefix)) {
        removeComponent(componentKey);
      }
    }
  }

  /** Clears the entire index. */
  public static synchronized void clear() {
    COMPONENT_TO_TYPES.clear();
    TYPE_TO_COMPONENTS.clear();
    RECORDING.remove();
  }
}
