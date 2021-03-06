package piuk.blockchain.android.ui.send;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.ColorRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.design.widget.CoordinatorLayout;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatEditText;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jakewharton.rxbinding2.widget.RxTextView;

import info.blockchain.wallet.contacts.data.Contact;
import info.blockchain.wallet.payload.data.Account;
import info.blockchain.wallet.payload.data.LegacyAddress;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.text.DecimalFormatSymbols;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.reactivex.android.schedulers.AndroidSchedulers;
import piuk.blockchain.android.R;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.connectivity.ConnectivityStatus;
import piuk.blockchain.android.data.contacts.models.PaymentRequestType;
import piuk.blockchain.android.data.rxjava.IgnorableDefaultObserver;
import piuk.blockchain.android.data.services.EventService;
import piuk.blockchain.android.databinding.AlertWatchOnlySpendBinding;
import piuk.blockchain.android.databinding.FragmentSendBinding;
import piuk.blockchain.android.injection.Injector;
import piuk.blockchain.android.ui.account.ItemAccount;
import piuk.blockchain.android.ui.account.PaymentConfirmationDetails;
import piuk.blockchain.android.ui.account.SecondPasswordHandler;
import piuk.blockchain.android.ui.balance.BalanceFragment;
import piuk.blockchain.android.ui.base.BaseAuthActivity;
import piuk.blockchain.android.ui.base.BaseFragment;
import piuk.blockchain.android.ui.chooser.AccountChooserActivity;
import piuk.blockchain.android.ui.confirm.ConfirmPaymentDialog;
import piuk.blockchain.android.ui.customviews.MaterialProgressDialog;
import piuk.blockchain.android.ui.customviews.NumericKeyboard;
import piuk.blockchain.android.ui.customviews.NumericKeyboardCallback;
import piuk.blockchain.android.ui.customviews.ToastCustom;
import piuk.blockchain.android.ui.home.MainActivity;
import piuk.blockchain.android.ui.zxing.CaptureActivity;
import piuk.blockchain.android.util.AppRate;
import piuk.blockchain.android.util.AppUtil;
import piuk.blockchain.android.util.EditTextFormatUtil;
import piuk.blockchain.android.util.PermissionUtil;
import piuk.blockchain.android.util.ViewUtils;
import piuk.blockchain.android.util.annotations.Thunk;
import timber.log.Timber;

import static android.databinding.DataBindingUtil.inflate;
import static piuk.blockchain.android.ui.chooser.AccountChooserActivity.EXTRA_SELECTED_ITEM;
import static piuk.blockchain.android.ui.chooser.AccountChooserActivity.EXTRA_SELECTED_OBJECT_TYPE;


public class SendFragment extends BaseFragment<SendView, SendPresenter>
        implements SendView, NumericKeyboardCallback {

    public static final String ARGUMENT_SCAN_DATA = "scan_data";
    public static final String ARGUMENT_SELECTED_ACCOUNT_POSITION = "selected_account_position";
    public static final String ARGUMENT_CONTACT_ID = "contact_id";
    public static final String ARGUMENT_CONTACT_MDID = "contact_mdid";
    public static final String ARGUMENT_FCTX_ID = "fctx_id";
    public static final String ARGUMENT_SCAN_DATA_ADDRESS_INPUT_ROUTE = "address_input_route";

    private static final int SCAN_URI = 2010;
    private static final int SCAN_PRIVX = 2011;
    private static final int COOL_DOWN_MILLIS = 2 * 1000;

    @Inject SendPresenter sendPresenter;

    @Thunk FragmentSendBinding binding;
    @Thunk AlertDialog transactionSuccessDialog;
    @Thunk boolean textChangeAllowed = true;
    private boolean contactsPayment;
    private OnSendFragmentInteractionListener listener;
    private MaterialProgressDialog progressDialog;
    private AlertDialog largeTxWarning;
    private ConfirmPaymentDialog confirmPaymentDialog;
    private NumericKeyboard customKeypad;

    private int selectedAccountPosition = -1;
    private long backPressed;
    private final Handler dialogHandler = new Handler();
    private final Runnable dialogRunnable = new Runnable() {
        @Override
        public void run() {
            if (transactionSuccessDialog != null && transactionSuccessDialog.isShowing()) {
                transactionSuccessDialog.dismiss();
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent.getAction().equals(BalanceFragment.ACTION_INTENT) && binding != null) {
                getPresenter().updateUI();
            }
        }
    };

    {
        Injector.getInstance().getPresenterComponent().inject(this);
    }

    public static SendFragment newInstance(@Nullable String scanData,
                                           String scanRoute,
                                           int selectedAccountPosition) {
        SendFragment fragment = new SendFragment();
        Bundle args = new Bundle();
        args.putString(ARGUMENT_SCAN_DATA, scanData);
        args.putString(ARGUMENT_SCAN_DATA_ADDRESS_INPUT_ROUTE, scanRoute);
        args.putInt(ARGUMENT_SELECTED_ACCOUNT_POSITION, selectedAccountPosition);
        fragment.setArguments(args);
        return fragment;
    }

    public static SendFragment newInstance(String uri,
                                           String contactId,
                                           String contactMdid,
                                           String fctxId) {
        SendFragment fragment = new SendFragment();
        Bundle args = new Bundle();
        args.putString(ARGUMENT_SCAN_DATA, uri);
        args.putString(ARGUMENT_CONTACT_ID, contactId);
        args.putString(ARGUMENT_CONTACT_MDID, contactMdid);
        args.putString(ARGUMENT_FCTX_ID, fctxId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        if (getArguments() != null) {
            selectedAccountPosition = getArguments().getInt(ARGUMENT_SELECTED_ACCOUNT_POSITION);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_send, container, false);
        onViewReady();
        setupViews();
        setCustomKeypad();
        setupFeesView();
        return binding.getRoot();
    }

    @Override
    public Bundle getFragmentBundle() {
        return getArguments();
    }

    @Override
    public void onResume() {
        super.onResume();
        closeKeypad();
        setupToolbar();
        IntentFilter filter = new IntentFilter(BalanceFragment.ACTION_INTENT);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(receiver, filter);
        getPresenter().updateUI();
    }

    @Override
    public void onPause() {
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(receiver);
        super.onPause();
    }

    private void setupToolbar() {
        if (((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((BaseAuthActivity) getActivity()).setupToolbar(
                    ((MainActivity) getActivity()).getSupportActionBar(), R.string.send_bitcoin);
        } else {
            finishPage(false);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (menu != null) menu.clear();
        inflater.inflate(R.menu.menu_send, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_qr:
                if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    PermissionUtil.requestCameraPermissionFromFragment(binding.getRoot(), this);
                } else {
                    startScanActivity(SCAN_URI);
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @SuppressWarnings({"ConstantConditions", "unchecked"})
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK && requestCode == SCAN_URI
                && data != null && data.getStringExtra(CaptureActivity.SCAN_RESULT) != null) {

            getPresenter().handleIncomingQRScan(data.getStringExtra(CaptureActivity.SCAN_RESULT),
                    EventService.EVENT_TX_INPUT_FROM_QR);

        } else if (requestCode == SCAN_PRIVX && resultCode == Activity.RESULT_OK) {
            final String scanData = data.getStringExtra(CaptureActivity.SCAN_RESULT);
            getPresenter().handleScannedDataForWatchOnlySpend(scanData);

            // Set Receiving account
        } else if (resultCode == Activity.RESULT_OK
                && requestCode == AccountChooserActivity.REQUEST_CODE_CHOOSE_RECEIVING_ACCOUNT_FROM_SEND
                && data != null) {

            try {
                Class type = Class.forName(data.getStringExtra(EXTRA_SELECTED_OBJECT_TYPE));
                Object object = new ObjectMapper().readValue(data.getStringExtra(EXTRA_SELECTED_ITEM), type);

                if (object instanceof Contact) {
                    getPresenter().setContact(((Contact) object));
                } else if (object instanceof Account) {
                    Account account = ((Account) object);
                    getPresenter().setContact(null);
                    getPresenter().setReceivingAddress(new ItemAccount(account.getLabel(), null, null, null, account, null));

                    String label = account.getLabel();
                    if (label == null || label.isEmpty()) {
                        label = account.getXpub();
                    }
                    binding.toContainer.toAddressEditTextView.setText(StringUtils.abbreviate(label, 32));
                } else if (object instanceof LegacyAddress) {
                    LegacyAddress legacyAddress = ((LegacyAddress) object);
                    getPresenter().setContact(null);
                    getPresenter().setReceivingAddress(new ItemAccount(legacyAddress.getLabel(), null, null, null, legacyAddress, legacyAddress.getAddress()));

                    String label = legacyAddress.getLabel();
                    if (label == null || label.isEmpty()) {
                        label = legacyAddress.getAddress();
                    }
                    binding.toContainer.toAddressEditTextView.setText(StringUtils.abbreviate(label, 32));
                }

            } catch (ClassNotFoundException | IOException e) {
                throw new RuntimeException(e);
            }
            // Set Sending account
        } else if (resultCode == Activity.RESULT_OK
                && requestCode == AccountChooserActivity.REQUEST_CODE_CHOOSE_SENDING_ACCOUNT_FROM_SEND
                && data != null) {

            try {
                Class type = Class.forName(data.getStringExtra(EXTRA_SELECTED_OBJECT_TYPE));
                Object object = new ObjectMapper().readValue(data.getStringExtra(EXTRA_SELECTED_ITEM), type);

                ItemAccount chosenItem = null;
                if (object instanceof Account) {
                    Account account = ((Account) object);
                    chosenItem = new ItemAccount(account.getLabel(), null, null, null, account, null);

                    String label = chosenItem.getLabel();
                    if (label == null || label.isEmpty()) {
                        label = account.getXpub();
                    }
                    binding.fromContainer.fromAddressTextView.setText(StringUtils.abbreviate(label, 32));

                } else if (object instanceof LegacyAddress) {
                    LegacyAddress legacyAddress = ((LegacyAddress) object);
                    chosenItem = new ItemAccount(legacyAddress.getLabel(), null, null, null, legacyAddress, legacyAddress.getAddress());

                    String label = chosenItem.getLabel();
                    if (label == null || label.isEmpty()) {
                        label = legacyAddress.getAddress();
                    }
                    binding.fromContainer.fromAddressTextView.setText(StringUtils.abbreviate(label, 32));
                }

                getPresenter().setSendingAddress(chosenItem);

                updateTotals(chosenItem);

            } catch (ClassNotFoundException | IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void onBackPressed() {
        if (isKeyboardVisible()) {
            closeKeypad();
        } else {
            handleBackPressed();
        }
    }

    private void handleBackPressed() {
        if (backPressed + COOL_DOWN_MILLIS > System.currentTimeMillis()) {
            AccessState.getInstance().logout(getContext());
            return;
        } else {
            onExitConfirmToast();
        }

        backPressed = System.currentTimeMillis();
    }

    public void onExitConfirmToast() {
        showToast(R.string.exit_confirm, ToastCustom.TYPE_GENERAL);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PermissionUtil.PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanActivity(SCAN_URI);
            } else {
                // Permission request was denied.
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startScanActivity(int code) {
        if (!new AppUtil(getActivity()).isCameraOpen()) {
            Intent intent = new Intent(getActivity(), CaptureActivity.class);
            startActivityForResult(intent, code);
        } else {
            showToast(R.string.camera_unavailable, ToastCustom.TYPE_ERROR);
        }
    }

    private void setupViews() {
        setupDestinationView();
        setupSendFromView();
        setupReceiveToView();

        setupBtcTextField();
        setupFiatTextField();

        binding.max.setOnClickListener(view ->
                getPresenter().spendAllClicked(getPresenter().getSendingItemAccount(), getFeePriority()));

        binding.buttonSend.setOnClickListener(v -> {
            if (ConnectivityStatus.hasConnectivity(getActivity())) {
                requestSendPayment();
            } else {
                showToast(R.string.check_connectivity_exit, ToastCustom.TYPE_ERROR);
            }
        });
    }

    private void setupFeesView() {
        FeePriorityAdapter adapter = new FeePriorityAdapter(getActivity(),
                getPresenter().getFeeOptionsForDropDown());

        binding.spinnerPriority.setAdapter(adapter);

        binding.spinnerPriority.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0:
                    case 1:
                        binding.buttonSend.setEnabled(true);
                        binding.textviewFeeAbsolute.setVisibility(View.VISIBLE);
                        binding.textviewFeeTime.setVisibility(View.VISIBLE);
                        binding.textInputLayout.setVisibility(View.GONE);
                        updateTotals(getPresenter().getSendingItemAccount());
                        break;
                    case 2:
                        if (getPresenter().shouldShowAdvancedFeeWarning()) {
                            alertCustomSpend();
                        } else {
                            displayCustomFeeField();
                        }
                        break;
                }

                DisplayFeeOptions options = getPresenter().getFeeOptionsForDropDown().get(position);
                binding.textviewFeeType.setText(options.getTitle());
                binding.textviewFeeTime.setText(position != 2 ? options.getDescription() : null);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // No-op
            }
        });

        binding.textviewFeeAbsolute.setOnClickListener(v -> binding.spinnerPriority.performClick());
        binding.textviewFeeType.setText(R.string.fee_options_regular);
        binding.textviewFeeTime.setText(R.string.fee_options_regular_time);

        RxTextView.textChanges(binding.amountContainer.amountBtc)
                .debounce(400, TimeUnit.MILLISECONDS)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        value -> updateTotals(getPresenter().getSendingItemAccount()),
                        Throwable::printStackTrace);

        RxTextView.textChanges(binding.amountContainer.amountFiat)
                .debounce(400, TimeUnit.MILLISECONDS)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        value -> updateTotals(getPresenter().getSendingItemAccount()),
                        Throwable::printStackTrace);
    }

    private void requestSendPayment() {
        getPresenter().onSendClicked(
                binding.amountContainer.amountBtc.getText().toString(),
                binding.toContainer.toAddressEditTextView.getText().toString(),
                getFeePriority());
    }

    private void setupDestinationView() {
        //Avoid OntouchListener - causes paste issues on some Samsung devices
        binding.toContainer.toAddressEditTextView.setOnClickListener(v -> {
            binding.toContainer.toAddressEditTextView.setText("");
            getPresenter().setReceivingAddress(null);
        });
        //LongClick listener required to clear receive address in memory when user long clicks to paste
        binding.toContainer.toAddressEditTextView.setOnLongClickListener(v -> {
            binding.toContainer.toAddressEditTextView.setText("");
            getPresenter().setReceivingAddress(null);
            v.performClick();
            return false;
        });

        //TextChanged listener required to invalidate receive address in memory when user
        //chooses to edit address populated via QR
        RxTextView.textChanges(binding.toContainer.toAddressEditTextView)
                .doOnNext(ignored -> {
                    if (getActivity().getCurrentFocus() == binding.toContainer.toAddressEditTextView) {
                        getPresenter().setReceivingAddress(null);
                        getPresenter().setContact(null);
                    }
                })
                .subscribe(new IgnorableDefaultObserver<>());
    }

    private void setupSendFromView() {
        ItemAccount itemAccount;
        if (selectedAccountPosition != -1) {
            itemAccount = getPresenter().getAddressList(getFeePriority()).get(selectedAccountPosition);
        } else {
            itemAccount = getPresenter().getAddressList(getFeePriority()).get(getPresenter().getDefaultAccount());
        }

        getPresenter().setSendingAddress(itemAccount);
        binding.fromContainer.fromAddressTextView.setText(itemAccount.getLabel());

        binding.fromContainer.fromAddressTextView.setOnClickListener(v -> startFromFragment());
        binding.fromContainer.fromArrowImage.setOnClickListener(v -> startFromFragment());
    }

    @Thunk
    void updateTotals(ItemAccount itemAccount) {
        getPresenter().calculateTransactionAmounts(itemAccount,
                binding.amountContainer.amountBtc.getText().toString(),
                getFeePriority(),
                null);
    }

    @FeeType.FeePriorityDef
    private int getFeePriority() {
        int position = binding.spinnerPriority.getSelectedItemPosition();
        switch (position) {
            case 1:
                return FeeType.FEE_OPTION_PRIORITY;
            case 2:
                return FeeType.FEE_OPTION_CUSTOM;
            case 0:
            default:
                return FeeType.FEE_OPTION_REGULAR;
        }
    }

    private void startFromFragment() {
        AccountChooserActivity.startForResult(this,
                AccountChooserActivity.REQUEST_CODE_CHOOSE_SENDING_ACCOUNT_FROM_SEND,
                PaymentRequestType.REQUEST,
                getString(R.string.from));
    }

    private void setupReceiveToView() {
        binding.toContainer.toArrowImage.setOnClickListener(v ->
                AccountChooserActivity.startForResult(this,
                        AccountChooserActivity.REQUEST_CODE_CHOOSE_RECEIVING_ACCOUNT_FROM_SEND,
                        PaymentRequestType.SEND,
                        getString(R.string.to)));
    }

    @Override
    public void lockContactsFields() {
        contactsPayment = true;
        binding.amountContainer.amountBtc.setEnabled(false);
        binding.amountContainer.amountFiat.setEnabled(false);
        binding.toContainer.toArrowImage.setVisibility(View.GONE);
        binding.toContainer.toArrowImage.setOnClickListener(null);
        binding.toContainer.toAddressEditTextView.setEnabled(false);
        binding.progressBarMaxAvailable.setVisibility(View.GONE);
        binding.max.setVisibility(View.GONE);
    }

    @Override
    public void hideSendingAddressField() {
        binding.fromContainer.fromConstraintLayout.setVisibility(View.GONE);
        binding.divider1.setVisibility(View.GONE);
    }

    @Override
    public void hideReceivingAddressField() {
        binding.toContainer.toAddressEditTextView.setHint(R.string.to_field_helper_no_dropdown);
    }

    @Override
    public void showInvalidAmount() {
        showToast(R.string.invalid_amount, ToastCustom.TYPE_ERROR);
    }

    @Override
    public void updateBtcAmount(String amount) {
        binding.amountContainer.amountBtc.setText(amount);
//        binding.amountContainer.amountBtc.setSelection(binding.amountContainer.amountBtc.getText().length());
    }

    @Override
    public void setDestinationAddress(String btcAddress) {
        binding.toContainer.toAddressEditTextView.setText(btcAddress);
    }

    @Override
    public void setMaxAvailable(String max) {
        binding.max.setText(max);
    }

    @Override
    public void setUnconfirmedFunds(String warning) {
        binding.unconfirmedFundsWarning.setText(warning);
    }

    @Override
    public void setContactName(String name) {
        binding.toContainer.toAddressEditTextView.setText(name);
    }

    @Override
    public void setMaxAvailableVisible(boolean visible) {
        if (!contactsPayment) {
            if (visible) {
                binding.max.setVisibility(View.VISIBLE);
                binding.progressBarMaxAvailable.setVisibility(View.INVISIBLE);
            } else {
                binding.max.setVisibility(View.INVISIBLE);
                binding.progressBarMaxAvailable.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void setMaxAvailableColor(@ColorRes int color) {
        binding.max.setTextColor(ContextCompat.getColor(getContext(), color));
    }

    @Override
    public void updateBtcUnit(String unit) {
        binding.amountContainer.currencyBtc.setText(unit);
    }

    @Override
    public void updateFiatUnit(String unit) {
        binding.amountContainer.currencyFiat.setText(unit);
    }

    @Override
    public void onSetSpendAllAmount(String textFromSatoshis) {
        binding.amountContainer.amountBtc.setText(textFromSatoshis);
    }

    @Override
    public void showToast(@StringRes int message, @ToastCustom.ToastType String toastType) {
        ToastCustom.makeText(getActivity(), getString(message), ToastCustom.LENGTH_SHORT, toastType);
    }

    @Override
    public void showSecondPasswordDialog() {
        new SecondPasswordHandler(getContext()).validate(new SecondPasswordHandler.ResultListener() {
            @Override
            public void onNoSecondPassword() {
                getPresenter().onNoSecondPassword();
            }

            @Override
            public void onSecondPasswordValidated(String validateSecondPassword) {
                getPresenter().onSecondPasswordValidated(validateSecondPassword);
            }
        });
    }

    @Override
    public void onShowTransactionSuccess(@Nullable String mdid,
                                         @Nullable String fctxId,
                                         String hash,
                                         long transactionValue) {
        playAudio();

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        View dialogView = View.inflate(getActivity(), R.layout.modal_transaction_success, null);
        transactionSuccessDialog = dialogBuilder.setView(dialogView)
                .setPositiveButton(getString(R.string.done), null)
                .create();
        transactionSuccessDialog.setTitle(R.string.transaction_submitted);

        AppRate appRate = new AppRate(getActivity())
                .setMinTransactionsUntilPrompt(3)
                .incrementTransactionCount();

        // If should show app rate, success dialog shows first and launches
        // rate dialog on dismiss. Dismissing rate dialog then closes the page. This will
        // happen if the user chooses to rate the app - they'll return to the main page.
        // Won't show if contact transaction, as other dialog takes preference
        if (appRate.shouldShowDialog() && fctxId == null) {
            AlertDialog ratingDialog = appRate.getRateDialog();
            ratingDialog.setOnDismissListener(d -> finishPage(true));
            transactionSuccessDialog.show();
            transactionSuccessDialog.setOnDismissListener(d -> ratingDialog.show());
        } else {
            transactionSuccessDialog.show();
            transactionSuccessDialog.setOnDismissListener(dialogInterface -> {
                if (fctxId != null) {
                    getPresenter().broadcastPaymentSuccess(mdid, hash, fctxId, transactionValue);
                } else {
                    finishPage(true);
                }
            });
        }

        dialogHandler.postDelayed(dialogRunnable, 5 * 1000);
    }

    @Override
    public void showBroadcastFailedDialog(String mdid,
                                          String txHash,
                                          String facilitatedTxId,
                                          long transactionValue) {

        new AlertDialog.Builder(getContext(), R.style.AlertDialogStyle)
                .setTitle(R.string.app_name)
                .setMessage(R.string.contacts_payment_sent_failed_message)
                .setPositiveButton(R.string.retry, (dialog, which) ->
                        getPresenter().broadcastPaymentSuccess(mdid, txHash, facilitatedTxId, transactionValue))
                .setCancelable(false)
                .create()
                .show();
    }

    @Override
    public void showBroadcastSuccessDialog() {
        new AlertDialog.Builder(getContext(), R.style.AlertDialogStyle)
                .setTitle(R.string.app_name)
                .setMessage(R.string.contacts_payment_sent_success)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(dialogInterface -> finishPage(true))
                .create()
                .show();
    }

    @Override
    public void onShowBIP38PassphrasePrompt(String scanData) {
        final AppCompatEditText password = new AppCompatEditText(getActivity());
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint(R.string.password);

        new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setTitle(R.string.app_name)
                .setMessage(R.string.bip38_password_entry)
                .setView(ViewUtils.getAlertDialogPaddedView(getContext(), password))
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) ->
                        getPresenter().spendFromWatchOnlyBIP38(password.getText().toString(), scanData))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void onShowLargeTransactionWarning() {
        if (largeTxWarning != null && largeTxWarning.isShowing()) {
            largeTxWarning.dismiss();
        }

        largeTxWarning = new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setCancelable(false)
                .setTitle(R.string.warning)
                .setMessage(R.string.large_tx_warning)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        largeTxWarning.show();
    }

    @Override
    public void onShowSpendFromWatchOnly(String address) {
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setTitle(R.string.privx_required)
                .setMessage(String.format(getString(R.string.watch_only_spend_instructionss), address))
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_continue, (dialog, whichButton) -> {
                    if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        PermissionUtil.requestCameraPermissionFromActivity(binding.getRoot(), getActivity());
                    } else {
                        startScanActivity(SCAN_PRIVX);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override
    public void navigateToAddNote(PaymentConfirmationDetails paymentConfirmationDetails,
                                  PaymentRequestType paymentRequestType,
                                  String contactId,
                                  long satoshis,
                                  int accountPosition) {
        if (listener != null) {
            listener.onTransactionNotesRequested(paymentConfirmationDetails,
                    paymentRequestType,
                    contactId,
                    satoshis,
                    accountPosition);
        }
    }

    // BTC Field
    @SuppressLint("NewApi")
    private void setupBtcTextField() {
        binding.amountContainer.amountBtc.setSelectAllOnFocus(true);
        binding.amountContainer.amountBtc.setHint("0" + getDefaultDecimalSeparator() + "00");
        binding.amountContainer.amountBtc.addTextChangedListener(btcTextWatcher);
        try {
            // This method is hidden but accessible on <API21, but here we catch exceptions just in case
            binding.amountContainer.amountBtc.setShowSoftInputOnFocus(false);
        } catch (Exception e) {
            Timber.e(e);
        }
    }

    // Fiat Field
    @SuppressLint("NewApi")
    private void setupFiatTextField() {
        binding.amountContainer.amountFiat.setHint("0" + getDefaultDecimalSeparator() + "00");
        binding.amountContainer.amountFiat.setSelectAllOnFocus(true);
        binding.amountContainer.amountFiat.addTextChangedListener(fiatTextWatcher);
        try {
            // This method is hidden but accessible on <API21, but here we catch exceptions just in case
            binding.amountContainer.amountFiat.setShowSoftInputOnFocus(false);
        } catch (Exception e) {
            Timber.e(e);
        }
    }

    @Override
    public void updateBtcTextField(String text) {
        binding.amountContainer.amountBtc.setText(text);
    }

    @Override
    public void updateFiatTextField(String text) {
        binding.amountContainer.amountFiat.setText(text);
    }

    private TextWatcher btcTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // No-op
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // No-op
        }

        @Override
        public void afterTextChanged(Editable s) {
            binding.amountContainer.amountBtc.removeTextChangedListener(this);
            s = EditTextFormatUtil.formatEditable(s,
                    getPresenter().getCurrencyHelper().getMaxBtcDecimalLength(),
                    binding.amountContainer.amountBtc,
                    getDefaultDecimalSeparator());

            binding.amountContainer.amountBtc.addTextChangedListener(this);

            if (textChangeAllowed) {
                textChangeAllowed = false;
                getPresenter().updateFiatTextField(s.toString());
                textChangeAllowed = true;
            }
        }
    };

    @Thunk
    String getDefaultDecimalSeparator() {
        return String.valueOf(DecimalFormatSymbols.getInstance().getDecimalSeparator());
    }

    private TextWatcher fiatTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // No-op
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // No-op
        }

        @Override
        public void afterTextChanged(Editable s) {
            binding.amountContainer.amountFiat.removeTextChangedListener(this);
            int maxLength = 2;
            s = EditTextFormatUtil.formatEditable(s,
                    maxLength,
                    binding.amountContainer.amountFiat,
                    getDefaultDecimalSeparator());

            binding.amountContainer.amountFiat.addTextChangedListener(this);

            if (textChangeAllowed) {
                textChangeAllowed = false;
                getPresenter().updateBtcTextField(s.toString());
                textChangeAllowed = true;
            }
        }
    };

    @Override
    public void onShowPaymentDetails(PaymentConfirmationDetails details, @Nullable String note) {
        confirmPaymentDialog = ConfirmPaymentDialog.newInstance(details, note, true);
        confirmPaymentDialog
                .show(getFragmentManager(), ConfirmPaymentDialog.class.getSimpleName());

        if (getPresenter().isLargeTransaction()) {
            binding.getRoot().postDelayed(this::onShowLargeTransactionWarning, 500);
        }
    }

    @Override
    public void onShowReceiveToWatchOnlyWarning(String address) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle);
        AlertWatchOnlySpendBinding dialogBinding = inflate(LayoutInflater.from(getActivity()),
                R.layout.alert_watch_only_spend, null, false);
        dialogBuilder.setView(dialogBinding.getRoot());
        dialogBuilder.setCancelable(false);

        final AlertDialog alertDialog = dialogBuilder.create();
        alertDialog.setCanceledOnTouchOutside(false);

        dialogBinding.confirmCancel.setOnClickListener(v -> {
            binding.toContainer.toAddressEditTextView.setText("");
            if (dialogBinding.confirmDontAskAgain.isChecked())
                getPresenter().disableWatchOnlySpendWarning();
            alertDialog.dismiss();
        });

        dialogBinding.confirmContinue.setOnClickListener(v -> {
            binding.toContainer.toAddressEditTextView.setText(address);
            if (dialogBinding.confirmDontAskAgain.isChecked())
                getPresenter().disableWatchOnlySpendWarning();
            alertDialog.dismiss();
        });

        alertDialog.show();
    }

    private void playAudio() {
        AudioManager audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null && audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            MediaPlayer mp;
            mp = MediaPlayer.create(getActivity().getApplicationContext(), R.raw.beep);
            mp.setOnCompletionListener(mp1 -> {
                mp1.reset();
                mp1.release();
            });
            mp.start();
        }
    }

    @Nullable
    @Override
    public String getClipboardContents() {
        ClipboardManager clipMan = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipMan.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            return clip.getItemAt(0).coerceToText(getActivity()).toString();
        }
        return null;
    }

    @Override
    public void showProgressDialog(int title) {
        progressDialog = new MaterialProgressDialog(getActivity());
        progressDialog.setCancelable(false);
        progressDialog.setMessage(R.string.please_wait);
        progressDialog.show();
    }

    @Override
    public void dismissProgressDialog() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    @Override
    public void finishPage(boolean paymentMade) {
        if (listener != null) listener.onSendFragmentClose(paymentMade);
    }

    public void onChangeFeeClicked() {
        confirmPaymentDialog.dismiss();
    }

    @Thunk
    void alertCustomSpend() {
        new AlertDialog.Builder(getActivity(), R.style.AlertDialogStyle)
                .setTitle(R.string.transaction_fee)
                .setMessage(R.string.fee_options_advanced_warning)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    getPresenter().disableAdvancedFeeWarning();
                    displayCustomFeeField();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        binding.spinnerPriority.setSelection(0))
                .show();
    }

    @Thunk
    void displayCustomFeeField() {
        binding.textviewFeeAbsolute.setVisibility(View.GONE);
        binding.textviewFeeTime.setVisibility(View.GONE);
        binding.textInputLayout.setVisibility(View.VISIBLE);
        binding.buttonSend.setEnabled(false);
        binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_hint));

        binding.edittextCustomFee.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus || !binding.edittextCustomFee.getText().toString().isEmpty()) {
                binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_inline_hint,
                        String.valueOf(getPresenter().getFeeOptions().getRegularFee()),
                        String.valueOf(getPresenter().getFeeOptions().getPriorityFee())));
            } else if (binding.edittextCustomFee.getText().toString().isEmpty()) {
                binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_hint));
            } else {
                binding.textInputLayout.setHint(getString(R.string.fee_options_sat_byte_hint));
            }
        });

        RxTextView.textChanges(binding.edittextCustomFee)
                .map(CharSequence::toString)
                .doOnNext(value -> binding.buttonSend.setEnabled(!value.isEmpty() && !value.equals("0")))
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .onErrorReturnItem(0L)
                .doOnNext(value -> {
                    if (value < getPresenter().getFeeOptions().getLimits().getMin()) {
                        binding.textInputLayout.setError(getString(R.string.fee_options_fee_too_low));
                    } else if (value > getPresenter().getFeeOptions().getLimits().getMax()) {
                        binding.textInputLayout.setError(getString(R.string.fee_options_fee_too_high));
                    } else {
                        binding.textInputLayout.setError(null);
                    }
                })
                .debounce(300, TimeUnit.MILLISECONDS)
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        value -> updateTotals(getPresenter().getSendingItemAccount()),
                        Throwable::printStackTrace);
    }

    @Override
    public long getCustomFeeValue() {
        String amount = binding.edittextCustomFee.getText().toString();
        return !amount.isEmpty() ? Long.valueOf(amount) : 0;
    }

    @Override
    public void updateFeeField(String fee) {
        binding.textviewFeeAbsolute.setText(fee);
    }

    public void onSendClicked() {
        if (ConnectivityStatus.hasConnectivity(getActivity())) {
            getPresenter().submitPayment();
        } else {
            showToast(R.string.check_connectivity_exit, ToastCustom.TYPE_ERROR);
        }
    }

    @Override
    protected SendPresenter createPresenter() {
        return sendPresenter;
    }

    @Override
    protected SendView getMvpView() {
        return this;
    }

    @Override
    public void dismissConfirmationDialog() {
        confirmPaymentDialog.dismiss();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnSendFragmentInteractionListener) {
            listener = (OnSendFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context + " must implement OnSendFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

    private void setCustomKeypad() {
        customKeypad = binding.keyboard;
        customKeypad.setCallback(this);
        customKeypad.setDecimalSeparator(getDefaultDecimalSeparator());

        // Enable custom keypad and disables default keyboard from popping up
        customKeypad.enableOnView(binding.amountContainer.amountBtc);
        customKeypad.enableOnView(binding.amountContainer.amountFiat);

        binding.amountContainer.amountBtc.setText("");
        binding.amountContainer.amountBtc.requestFocus();
    }

    private void closeKeypad() {
        customKeypad.setNumpadVisibility(View.GONE);
    }

    public boolean isKeyboardVisible() {
        return customKeypad.isVisible();
    }

    @Override
    public void onKeypadClose() {
        // Show bottom nav if applicable
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).getBottomNavigationView().restoreBottomNavigation();
        }

        // Resize activity to default
        binding.scrollView.setPadding(0, 0, 0, 0);
        CoordinatorLayout.LayoutParams layoutParams = new CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT);
        layoutParams.setMargins(0,
                0,
                0,
                (int) getActivity().getResources().getDimension(R.dimen.action_bar_height));
        binding.scrollView.setLayoutParams(layoutParams);
    }

    @Override
    public void onKeypadOpen() {
        // Hide bottom nav if applicable
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).getBottomNavigationView().hideBottomNavigation();
        }
    }

    @Override
    public void onKeypadOpenCompleted() {
        // Resize activity around view
        int translationY = binding.keyboard.getHeight();
        binding.scrollView.setPadding(0, 0, 0, translationY);

        CoordinatorLayout.LayoutParams layoutParams = new CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT);
        layoutParams.setMargins(0, 0, 0, 0);
        binding.scrollView.setLayoutParams(layoutParams);
    }

    public interface OnSendFragmentInteractionListener {

        void onSendFragmentClose(boolean paymentMade);

        void onTransactionNotesRequested(PaymentConfirmationDetails paymentConfirmationDetails,
                                         PaymentRequestType paymentRequestType,
                                         String contactId,
                                         long satoshis,
                                         int accountPosition);
    }
}
