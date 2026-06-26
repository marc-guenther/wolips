package org.objectstyle.wolips.bindings.preferences;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.objectstyle.wolips.bindings.Activator;
import org.objectstyle.wolips.bindings.utils.ValidationProfiler;

/**
 * Memoizes WOLips validation severity preferences so the validation pipeline
 * does not re-read them from the preference store for every element and binding.
 *
 * <p>Validation reads roughly a dozen severity preferences per binding (see
 * {@code AbstractWodElement.fillInProblems} / {@code AbstractWodBinding.fillInBindingProblems});
 * multiplied across every binding in a component that was a lot of redundant
 * store lookups. Values are cached on first read and the entire cache is
 * cleared whenever any plugin preference changes, so callers always observe
 * up-to-date values.</p>
 *
 * <p>Reads go through {@link Activator#getPluginPreferences()} -- exactly the
 * same store the un-cached call sites used -- so cached values are identical to
 * a live read. The invalidation listener is registered on the JFace
 * {@link org.eclipse.jface.preference.IPreferenceStore}, which is where the
 * preference pages and the {@link PreferenceInitializer} write, so any change a
 * user makes clears the cache.</p>
 */
public final class ValidationPreferences {
  private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<String, String>();

  private static volatile boolean listening;

  private ValidationPreferences() {
    // utility class
  }

  /**
   * Returns the cached value for the given preference key, reading it from the
   * plugin preferences on the first request. Equivalent to
   * {@code Activator.getDefault().getPluginPreferences().getString(key)}.
   */
  public static String getString(String key) {
    String value = CACHE.get(key);
    if (value == null) {
      ValidationProfiler.count(ValidationProfiler.PREFERENCE_READ);
      ensureListening();
      value = Activator.getDefault().getPluginPreferences().getString(key);
      CACHE.put(key, value);
    }
    else {
      ValidationProfiler.count(ValidationProfiler.PREFERENCE_CACHE_HIT);
    }
    return value;
  }

  /** Clears the cache so the next read reloads from the store. */
  public static void clear() {
    CACHE.clear();
  }

  private static void ensureListening() {
    if (listening) {
      return;
    }
    synchronized (ValidationPreferences.class) {
      if (listening) {
        return;
      }
      Activator activator = Activator.getDefault();
      if (activator == null) {
        // Plugin not started yet; don't register or mark as listening so a
        // later call retries. Values read now simply won't be invalidated.
        return;
      }
      activator.getPreferenceStore().addPropertyChangeListener(new IPropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent event) {
          CACHE.clear();
        }
      });
      listening = true;
    }
  }
}
