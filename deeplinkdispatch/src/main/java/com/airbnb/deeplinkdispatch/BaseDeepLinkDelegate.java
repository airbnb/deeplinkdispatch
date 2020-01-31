package com.airbnb.deeplinkdispatch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.TaskStackBuilder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@SuppressWarnings({"WeakerAccess", "unused"})
public class BaseDeepLinkDelegate {

  protected static final String TAG = "DeepLinkDelegate";

  protected final List<? extends BaseRegistry> registries;
  /**
   * <p>Value for DLD to substitute for declared replaceablePathVariables.</p>
   * <p>Example</p>
   * Given:
   * <ul>
   * <li><xmp>@DeepLink("https://www.example.com/-replaceable-path-variable-/users/{param1}")
   * </xmp></li>
   * <li>pathVariableReplacementValue = "obamaOs"</li>
   * </ul>
   * Then:
   * <ul><li><xmp>https://www.example.com/obamaOs/users/{param1}</xmp> will match.</li></ul>
   */
  protected final Map<String, String> pathVariableReplacements;

  public List<? extends BaseRegistry> getRegistries() {
    return registries;
  }

  public BaseDeepLinkDelegate(List<? extends BaseRegistry> registries) {
    this.registries = registries;
    pathVariableReplacements = new HashMap<>();
  }

  public BaseDeepLinkDelegate(
    List<? extends BaseRegistry> registries,
    Map<String, String> pathVariableReplacements
  ) {
    this.registries = registries;
    this.pathVariableReplacements = pathVariableReplacements;
    validatePathVariableReplacements(registries, pathVariableReplacements);
  }

  private DeepLinkEntry findEntry(String uriString) {
    DeepLinkEntry entryRegExpMatch = null;
    DeepLinkEntry entryIdxMatch;
    DeepLinkUri parse = DeepLinkUri.parse(uriString);
    for (BaseRegistry registry : registries) {
      entryIdxMatch = registry.idxMatch(parse, pathVariableReplacements);
      if (entryIdxMatch != null) {
        return entryIdxMatch;
      }
    }
    return null;
  }

  /**
   * Calls into {@link #dispatchFrom(Activity activity, Intent sourceIntent)}.
   *
   * @param activity activity with inbound Intent stored on it.
   * @return DeepLinkResult, whether success or error.
   */
  public DeepLinkResult dispatchFrom(Activity activity) {
    if (activity == null) {
      throw new NullPointerException("activity == null");
    }
    return dispatchFrom(activity, activity.getIntent());
  }

  /**
   * Calls {@link #createResult(Activity, Intent, DeepLinkEntry)}. If the DeepLinkResult has
   * a non-null TaskStackBuilder or Intent, it starts it. It always calls
   * {@link #notifyListener(Context, boolean, Uri, String, String)}.
   *
   * @param activity     used to startActivity() or notifyListener().
   * @param sourceIntent inbound Intent.
   * @return DeepLinkResult
   */
  public DeepLinkResult dispatchFrom(Activity activity, Intent sourceIntent) {
    DeepLinkResult result = createResult(activity, sourceIntent,
      findEntry(sourceIntent.getData().toString())
    );
    if (result.getTaskStackBuilder() != null) {
      result.getTaskStackBuilder().startActivities();
    } else if (result.getIntent() != null) {
      activity.startActivity(result.getIntent());
    }
    notifyListener(activity, !result.isSuccessful(), sourceIntent.getData(),
      result.getDeepLinkEntry() != null ? result.getDeepLinkEntry().getUriTemplate()
        : null, result.getError());
    return result;
  }

  /**
   * Create a {@link DeepLinkResult}, whether we are able to match the uri on
   * {@param sourceIntent} or not.
   *
   * @param activity      used to startActivity() or notifyListener().
   * @param sourceIntent  inbound Intent.
   * @param deepLinkEntry deepLinkEntry that matches the sourceIntent's URI. Derived from
   *                      {@link #findEntry(String)}. Can be injected for testing.
   * @return DeepLinkResult
   */
  public @NonNull
  DeepLinkResult createResult(
    Activity activity, Intent sourceIntent, DeepLinkEntry deepLinkEntry
  ) {
    if (activity == null) {
      throw new NullPointerException("activity == null");
    }
    if (sourceIntent == null) {
      throw new NullPointerException("sourceIntent == null");
    }
    Uri uri = sourceIntent.getData();
    if (uri == null) {
      return new DeepLinkResult(
        false, null, "No Uri in given activity's intent.", null, null, deepLinkEntry);
    }
    String uriString = uri.toString();
    if (deepLinkEntry == null) {
      return new DeepLinkResult(false, null, "DeepLinkEntry cannot be null",
        null, null, null);
    }
    DeepLinkUri deepLinkUri = DeepLinkUri.parse(uriString);
    Map<String, String> parameterMap = new HashMap<>(deepLinkEntry.getParameters(deepLinkUri));
    for (String queryParameter : deepLinkUri.queryParameterNames()) {
      for (String queryParameterValue : deepLinkUri.queryParameterValues(queryParameter)) {
        if (parameterMap.containsKey(queryParameter)) {
          Log.w(TAG, "Duplicate parameter name in path and query param: " + queryParameter);
        }
        parameterMap.put(queryParameter, queryParameterValue);
      }
    }
    parameterMap.put(DeepLink.URI, uri.toString());
    Bundle parameters;
    if (sourceIntent.getExtras() != null) {
      parameters = new Bundle(sourceIntent.getExtras());
    } else {
      parameters = new Bundle();
    }
    for (Map.Entry<String, String> parameterEntry : parameterMap.entrySet()) {
      parameters.putString(parameterEntry.getKey(), parameterEntry.getValue());
    }
    try {
      Class<?> c = deepLinkEntry.getActivityClass();
      Intent newIntent = null;
      TaskStackBuilder taskStackBuilder = null;
      if (deepLinkEntry.getType() == DeepLinkEntry.Type.CLASS) {
        newIntent = new Intent(activity, c);
      } else {
        Method method;
        DeepLinkResult errorResult = new DeepLinkResult(false, uriString,
          "Could not deep link to method: " + deepLinkEntry.getMethod() + " intents length == 0",
          null, null, deepLinkEntry);
        try {
          method = c.getMethod(deepLinkEntry.getMethod(), Context.class);
          if (method.getReturnType().equals(TaskStackBuilder.class)) {
            taskStackBuilder = (TaskStackBuilder) method.invoke(c, activity);
            if (taskStackBuilder.getIntentCount() == 0) {
              return errorResult;
            }
            newIntent = taskStackBuilder.editIntentAt(taskStackBuilder.getIntentCount() - 1);
          } else {
            newIntent = (Intent) method.invoke(c, activity);
          }
        } catch (NoSuchMethodException exception) {
          method = c.getMethod(deepLinkEntry.getMethod(), Context.class, Bundle.class);
          if (method.getReturnType().equals(TaskStackBuilder.class)) {
            taskStackBuilder = (TaskStackBuilder) method.invoke(c, activity, parameters);
            if (taskStackBuilder.getIntentCount() == 0) {
              return errorResult;
            }
            newIntent = taskStackBuilder.editIntentAt(taskStackBuilder.getIntentCount() - 1);
          } else {
            newIntent = (Intent) method.invoke(c, activity, parameters);
          }
        }
      }
      if (newIntent == null) {
        return new DeepLinkResult(false, uriString, "Destination Intent is null!", null,
          taskStackBuilder, deepLinkEntry);
      }
      if (newIntent.getAction() == null) {
        newIntent.setAction(sourceIntent.getAction());
      }
      if (newIntent.getData() == null) {
        newIntent.setData(sourceIntent.getData());
      }
      newIntent.putExtras(parameters);
      newIntent.putExtra(DeepLink.IS_DEEP_LINK, true);
      newIntent.putExtra(DeepLink.REFERRER_URI, uri);
      if (activity.getCallingActivity() != null) {
        newIntent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
      }
      return new DeepLinkResult(true, uriString, "", newIntent, taskStackBuilder, deepLinkEntry);
    } catch (NoSuchMethodException exception) {
      return new DeepLinkResult(false, uriString, "Deep link to non-existent method: "
        + deepLinkEntry.getMethod(), null, null, deepLinkEntry);
    } catch (IllegalAccessException exception) {
      return new DeepLinkResult(false, uriString, "Could not deep link to method: "
        + deepLinkEntry.getMethod(), null, null, deepLinkEntry);
    } catch (InvocationTargetException exception) {
      return new DeepLinkResult(false, uriString, "Could not deep link to method: "
        + deepLinkEntry.getMethod(), null, null, deepLinkEntry);
    }
  }

  private static void notifyListener(Context context, boolean isError, Uri uri,
                                     String uriTemplate, String errorMessage) {
    Intent intent = new Intent();
    intent.setAction(DeepLinkHandler.ACTION);
    intent.putExtra(DeepLinkHandler.EXTRA_URI, uri != null ? uri.toString() : "");
    intent.putExtra(DeepLinkHandler.EXTRA_URI_TEMPLATE, uriTemplate != null ? uriTemplate : "");
    intent.putExtra(DeepLinkHandler.EXTRA_SUCCESSFUL, !isError);
    if (isError) {
      intent.putExtra(DeepLinkHandler.EXTRA_ERROR_MESSAGE, errorMessage);
    }
    LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
  }

  public boolean supportsUri(String uriString) {
    return findEntry(uriString) != null;
  }

  /**
   * Validate a user's configuration of pathVariableReplacementValue.
   */
  private void validatePathVariableReplacementValue(String pathVariableReplacementValue) {
    if (pathVariableReplacementValue.matches("[a-zA-Z0-9/-]*"))
      throw new RuntimeException("Only a-z, A-Z, 0-9, and - are allowed in a "
        + "pathVariableReplacementValue. Currently it is: " + pathVariableReplacementValue);
  }

  /**
   * Ensure that every key-to-be-replaced declared by all registries have a corresponding key in
   * the user's injected mapping of pathVariableReplacements. If not, throw an exception and tell
   * the user which keys aren't present.
   * @param registries
   * @param pathVariableReplacements
   */
  private void validatePathVariableReplacements(List<? extends BaseRegistry> registries, Map<String,
    String> pathVariableReplacements) {
    HashSet<String> keysUnion = new HashSet<>();
    for (BaseRegistry registry : registries) {
      keysUnion.addAll(registry.getPathSegmentKeysInRegistry());
    }
    StringBuilder keysMissing = new StringBuilder();
    StringBuilder keysInMapping = new StringBuilder();
    for (String key : keysUnion) {
      if (!pathVariableReplacements.containsKey(key)) {
        keysMissing.append(key).append(",\n");
      }
    }
    if (keysMissing.length() > 0) {
      //We only need this list if we're reporting an error
      for (String s : pathVariableReplacements.keySet()) {
        keysInMapping.append(s).append(", ");
      }
      keysInMapping.delete(keysInMapping.length() - 2, keysInMapping.length() - 1);
      keysMissing.delete(keysMissing.length() - 2, keysMissing.length() - 1);

      throw new IllegalArgumentException("Keys not found in BaseDeepLinkDelegate's mapping of "
        + "PathVariableReplacementValues. Missing keys are:\n" + keysMissing.toString()
        + "Keys in mapping are: " + keysInMapping.toString() + ".");
    }
  }
}
