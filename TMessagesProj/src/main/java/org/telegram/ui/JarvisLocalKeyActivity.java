package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.jarvis.JarvisLocalCredential;
import org.telegram.messenger.jarvis.JarvisLocalKeyStore;
import org.telegram.messenger.jarvis.JarvisLocalModelClient;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Device-local provider configuration. Everything on this screen stays on the
 * phone: the key is validated against the provider from this device and then
 * written to the encrypted, account-isolated key store. No value here is ever
 * sent to Talky.
 */
public class JarvisLocalKeyActivity extends BaseFragment {

    private final JarvisLocalCredential existing;
    /** Saved secret, kept so an untouched key field preserves it. */
    private final String existingKey;

    private JarvisLocalCredential.Provider provider;
    private JarvisLocalCredential.Format format;
    private final ArrayList<String> models = new ArrayList<>();
    private String selectedModel;
    private boolean saving;

    private TextSettingsCell providerCell;
    private EditTextCell baseUrlCell;
    private TextSettingsCell formatCell;
    private EditTextCell keyCell;
    private LinearLayout modelsContainer;
    private TextInfoPrivacyCell storageInfo;
    private TextInfoPrivacyCell modelHint;
    private TextInfoPrivacyCell formatWarning;

    public JarvisLocalKeyActivity(JarvisLocalCredential existing) {
        this.existing = existing;
        if (existing != null) {
            provider = existing.provider;
            format = existing.format;
            models.addAll(existing.models);
            selectedModel = existing.model;
            existingKey = existing.secretForEditing();
        } else {
            provider = JarvisLocalCredential.Provider.OPENAI;
            format = provider.format;
            models.add(provider.model);
            selectedModel = provider.model;
            existingKey = null;
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.JarvisLocalKey));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    save();
                }
            }
        });
        actionBar.createMenu().addItemWithWidth(1, R.drawable.ic_ab_done, dp(56),
                LocaleController.getString(R.string.Done));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setClipToPadding(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        frameLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        HeaderCell connectionHeader = new HeaderCell(context, getResourceProvider());
        connectionHeader.setText(t("Provider", "供应商"));
        content.addView(connectionHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        providerCell = new TextSettingsCell(context, getResourceProvider());
        providerCell.setBackground(Theme.getSelectorDrawable(true, getResourceProvider()));
        providerCell.setOnClickListener(v -> presentProviderPicker());
        content.addView(providerCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        formatCell = new TextSettingsCell(context, getResourceProvider());
        formatCell.setBackground(Theme.getSelectorDrawable(true, getResourceProvider()));
        formatCell.setOnClickListener(v -> presentFormatPicker());
        content.addView(formatCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        formatWarning = new TextInfoPrivacyCell(context, getResourceProvider());
        formatWarning.setTextColorByKey(Theme.key_text_RedRegular);
        formatWarning.setVisibility(View.GONE);
        content.addView(formatWarning, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell endpointHeader = new HeaderCell(context, getResourceProvider());
        endpointHeader.setText(t("Base URL", "Base URL"));
        content.addView(endpointHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        baseUrlCell = new EditTextCell(context, t("https://api.example.com/v1", "https://api.example.com/v1"), false, false,
                -1, getResourceProvider());
        baseUrlCell.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseUrlCell.editText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        baseUrlCell.setText(existing != null ? existing.baseUrl : provider.baseUrl);
        baseUrlCell.editText.setContentDescription(t("Base URL", "Base URL"));
        baseUrlCell.setDivider(true);
        baseUrlCell.editText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateStorageInfo();
            }
        });
        content.addView(baseUrlCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell baseUrlHint = new TextInfoPrivacyCell(context, getResourceProvider());
        baseUrlHint.setText(t(
                "The Base URL is where this device sends requests. Change it to use a gateway or a proxy; it must speak the API format chosen above.",
                "Base URL 是这台设备发送请求的地址，可改成网关或代理；它需要与上方选择的 API 格式匹配。"));
        content.addView(baseUrlHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell credentialHeader = new HeaderCell(context, getResourceProvider());
        credentialHeader.setText(t("Credential", "凭据"));
        content.addView(credentialHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // The saved key is never shown or prefilled: an empty field keeps it.
        keyCell = new EditTextCell(context, existingKey != null
                        ? t("Leave empty to keep the saved key", "留空则保留已保存的 Key")
                        : t("API key", "API Key"),
                false, false, -1, getResourceProvider());
        keyCell.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyCell.editText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        keyCell.editText.setContentDescription(t("API key", "API Key"));
        content.addView(keyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        storageInfo = new TextInfoPrivacyCell(context, getResourceProvider());
        content.addView(storageInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell modelsHeader = new HeaderCell(context, getResourceProvider());
        modelsHeader.setText(t("Models", "模型"));
        content.addView(modelsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        modelsContainer = new LinearLayout(context);
        modelsContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(modelsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextSettingsCell addModel = new TextSettingsCell(context, getResourceProvider());
        addModel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, getResourceProvider()));
        addModel.setBackground(Theme.getSelectorDrawable(true, getResourceProvider()));
        addModel.setText(t("Add model", "添加模型"), false);
        addModel.setOnClickListener(v -> presentAddModelDialog());
        content.addView(addModel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        modelHint = new TextInfoPrivacyCell(context, getResourceProvider());
        modelHint.setText(t(
                "The selected model is used for /ask on this device. Tap a model to select it, press and hold to remove it.",
                "本机 /ask 使用选中的模型。点击模型选中，长按删除。"));
        content.addView(modelHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell footer = new TextInfoPrivacyCell(context, getResourceProvider());
        footer.setText(t(
                "Stored only on this device, encrypted with Android Keystore, and never sent to Talky. Settings apply to this account on this device only.",
                "只保存在这台设备，使用 Android Keystore 加密，不会发往 Talky。配置只对当前账号、当前设备生效。"));
        content.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        refresh();
        return fragmentView;
    }

    private String t(String en, String zh) {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && locale.getLanguage().startsWith("zh") ? zh : en;
    }

    private void refresh() {
        if (providerCell == null) {
            return;
        }
        providerCell.setTextAndValue(t("Preset", "预设"), providerName(provider), true);
        formatCell.setTextAndValue(t("API format", "API 格式"), format.label, false);
        updateFormatWarning();
        rebuildModels();
        updateStorageInfo();
    }

    /**
     * A preset and the chosen format can disagree (for example DeepSeek's
     * address with the Anthropic transport). Validation would only fail after
     * a network round trip, so flag it inline while the form is edited.
     */
    private void updateFormatWarning() {
        if (formatWarning == null) {
            return;
        }
        boolean mismatch = provider != JarvisLocalCredential.Provider.CUSTOM && format != provider.format;
        if (mismatch) {
            formatWarning.setText(t(
                    "This API format does not match the preset. Check that the Base URL speaks it.",
                    "该 API 格式与预设不一致，请确认 Base URL 支持这种格式。"));
            formatWarning.setVisibility(View.VISIBLE);
        } else {
            formatWarning.setVisibility(View.GONE);
        }
    }

    private void updateStorageInfo() {
        if (storageInfo == null || baseUrlCell == null) {
            return;
        }
        boolean cleartext = baseUrlCell.editText.getText().toString().trim()
                .toLowerCase(Locale.ROOT).startsWith("http://");
        storageInfo.setText(cleartext
                ? t("This Base URL uses plain HTTP. The key travels unencrypted on the network; use HTTPS unless you control the whole path.",
                    "该 Base URL 使用明文 HTTP，Key 会在网络上明文传输。除非整条链路都由你控制，否则请使用 HTTPS。")
                : t("The key is encrypted on this device and sent only to the Base URL above.",
                    "Key 加密保存在本机，只会发送到上面的 Base URL。"));
        storageInfo.setTextColorByKey(cleartext ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText4);
    }

    private String providerName(JarvisLocalCredential.Provider value) {
        switch (value) {
            case OPENAI:
                return "OpenAI";
            case DEEPSEEK:
                return "DeepSeek";
            case ANTHROPIC:
                return "Anthropic";
            case GEMINI:
                return "Gemini";
            case CUSTOM:
            default:
                return t("Custom", "自定义");
        }
    }

    private void presentProviderPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final JarvisLocalCredential.Provider[] values = {
                JarvisLocalCredential.Provider.OPENAI,
                JarvisLocalCredential.Provider.DEEPSEEK,
                JarvisLocalCredential.Provider.ANTHROPIC,
                JarvisLocalCredential.Provider.GEMINI,
                JarvisLocalCredential.Provider.CUSTOM
        };
        CharSequence[] names = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = providerName(values[i]);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Choose a preset", "选择预设"));
        builder.setItems(names, (dialog, which) -> {
            if (values[which] == provider) {
                return;
            }
            provider = values[which];
            format = provider.format;
            models.clear();
            selectedModel = null;
            if (provider != JarvisLocalCredential.Provider.CUSTOM) {
                baseUrlCell.setText(provider.baseUrl);
                models.add(provider.model);
                selectedModel = provider.model;
            } else {
                // A custom endpoint starts blank; stale preset values would
                // otherwise be saved under the wrong provider.
                baseUrlCell.setText("");
            }
            refresh();
        });
        showDialog(builder.create());
    }

    private void presentFormatPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final JarvisLocalCredential.Format[] values = JarvisLocalCredential.Format.values();
        CharSequence[] names = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].label;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("API format", "API 格式"));
        builder.setItems(names, (dialog, which) -> {
            format = values[which];
            refresh();
        });
        showDialog(builder.create());
    }

    private void rebuildModels() {
        if (modelsContainer == null) {
            return;
        }
        modelsContainer.removeAllViews();
        if (selectedModel == null || !models.contains(selectedModel)) {
            selectedModel = models.isEmpty() ? null : models.get(0);
        }
        for (int i = 0; i < models.size(); i++) {
            final String model = models.get(i);
            TextRadioCell cell = new TextRadioCell(getContext());
            cell.setTextAndCheck(model, model.equals(selectedModel), i != models.size() - 1);
            cell.setBackground(Theme.getSelectorDrawable(true, getResourceProvider()));
            cell.setOnClickListener(v -> {
                selectedModel = model;
                rebuildModels();
            });
            cell.setOnLongClickListener(v -> {
                confirmRemoveModel(model);
                return true;
            });
            modelsContainer.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (models.isEmpty()) {
            TextInfoPrivacyCell empty = new TextInfoPrivacyCell(getContext(), getResourceProvider());
            empty.setText(t("Add at least one model to continue.", "至少添加一个模型才能保存。"));
            modelsContainer.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void confirmRemoveModel(String model) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(t("Remove model?", "删除模型？"));
        builder.setMessage(model);
        builder.setPositiveButton(t("Remove", "删除"), (dialog, which) -> {
            models.remove(model);
            rebuildModels();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void presentAddModelDialog() {
        if (getParentActivity() == null || models.size() >= JarvisLocalCredential.MAX_MODELS) {
            return;
        }
        Context context = getParentActivity();
        EditText input = new EditText(context);
        input.setHint(t("e.g. gpt-4o-mini", "例如 gpt-4o-mini"));
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setPadding(dp(24), dp(12), dp(24), dp(12));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(t("Add model", "添加模型"));
        builder.setView(input);
        builder.setPositiveButton(t("Add", "添加"), (dialog, which) -> {
            String value = input.getText().toString().trim();
            if (TextUtils.isEmpty(value) || value.length() > JarvisLocalCredential.MAX_MODEL_LENGTH) {
                return;
            }
            for (int i = 0; i < models.size(); i++) {
                if (models.get(i).equalsIgnoreCase(value)) {
                    selectedModel = models.get(i);
                    rebuildModels();
                    return;
                }
            }
            models.add(value);
            selectedModel = value;
            rebuildModels();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void save() {
        if (saving || getParentActivity() == null) {
            return;
        }
        final String baseUrl = baseUrlCell.editText.getText().toString().trim();
        final String typedKey = keyCell.editText.getText().toString().trim();
        final String key = typedKey.isEmpty() && existingKey != null ? existingKey : typedKey;
        if (models.isEmpty() || selectedModel == null) {
            showAlert(t("Add at least one model.", "请至少添加一个模型。"));
            return;
        }
        if (key.isEmpty()) {
            showAlert(t("Enter an API key.", "请填写 API Key。"));
            return;
        }
        final JarvisLocalCredential credential;
        try {
            credential = new JarvisLocalCredential(provider, format, baseUrl, models, selectedModel, key);
        } catch (Exception ignored) {
            showAlert(t("Check the Base URL, the API key and at least one model name.",
                    "请检查 Base URL、API Key 和模型名是否填写正确。"));
            return;
        }
        saving = true;
        actionBar.setSubtitle(t("Verifying…", "正在验证…"));
        final long userId = getUserConfig().getClientUserId();
        Utilities.globalQueue.postRunnable(() -> {
            JarvisLocalKeyStore store = null;
            try {
                store = new JarvisLocalKeyStore(ApplicationLoader.applicationContext, userId);
            } catch (Exception ignored) {
            }
            try {
                new JarvisLocalModelClient().validate(credential);
                if (store == null) {
                    throw new Exception("secure storage unavailable");
                }
                store.save(credential);
                final JarvisLocalKeyStore finalStore = store;
                AndroidUtilities.runOnUIThread(() -> {
                    saving = false;
                    if (actionBar != null) {
                        actionBar.setSubtitle(null);
                    }
                    if (getUserConfig().getClientUserId() != userId) {
                        return;
                    }
                    if (getParentActivity() != null) {
                        onCredentialSaved();
                    }
                });
            } catch (JarvisLocalModelClient.ModelException error) {
                AndroidUtilities.runOnUIThread(() -> {
                    saving = false;
                    if (actionBar != null) {
                        actionBar.setSubtitle(null);
                    }
                    showAlert(localModelMessage(error.failure));
                });
            } catch (Exception ignored) {
                AndroidUtilities.runOnUIThread(() -> {
                    saving = false;
                    if (actionBar != null) {
                        actionBar.setSubtitle(null);
                    }
                    showAlert(t("The key could not be saved in secure storage.", "Key 无法写入安全存储。"));
                });
            }
        });
    }

    private void onCredentialSaved() {
        removeSelfFromStack();
    }

    private String localModelMessage(JarvisLocalModelClient.Failure failure) {
        switch (failure) {
            case PROVIDER_REJECTED:
                return t("The provider rejected this key or model. Check the API key and the model name.",
                        "服务商拒绝了该 Key 或模型，请核对 API Key 与模型名。");
            case RATE_LIMITED:
                return t("The provider is rate-limiting this key. Try again later.", "供应商对该 Key 限流，请稍后再试。");
            case INVALID_CONFIGURATION:
                return t("Enter a Base URL, an API key and at least one model.", "请填写 Base URL、API Key 和至少一个模型。");
            case INVALID_RESPONSE:
            case UNAVAILABLE:
            default:
                return t("The key could not be verified. Check the Base URL, the API format and your connection.",
                        "无法验证 Key，请检查 Base URL、API 格式和网络连接。");
        }
    }

    private void showAlert(String message) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.JarvisLocalKey));
        builder.setMessage(message);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }
}
