package com.sparrowwallet.sparrow.wallet;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.WalletTabData;
import com.sparrowwallet.sparrow.control.WalletIcon;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.ImageUtils;
import com.sparrowwallet.sparrow.io.StorageException;
import com.sparrowwallet.sparrow.net.AllHistoryChangedException;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import com.sparrowwallet.sparrow.io.Storage;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.reactivex.subjects.PublishSubject;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.sparrowwallet.drongo.wallet.WalletNode.nodeRangesToString;

public class WalletForm {
    private static final Logger log = LoggerFactory.getLogger(WalletForm.class);

    private final Storage storage;
    protected Wallet wallet;

    private final PublishSubject<WalletNode> refreshNodesSubject;

    private final List<WalletForm> nestedWalletForms = new ArrayList<>();

    private WalletTransactionsEntry walletTransactionsEntry;
    private WalletUtxosEntry walletUtxosEntry;
    private final List<NodeEntry> accountEntries = new ArrayList<>();
    private final List<Set<WalletNode>> walletTransactionNodes = new ArrayList<>();
    private final ObjectProperty<WalletTransaction> createdWalletTransactionProperty = new SimpleObjectProperty<>(null);

    private ElectrumServer.TransactionMempoolService transactionMempoolService;

    private final BooleanProperty lockedProperty = new SimpleBooleanProperty(false);

    public WalletForm(Storage storage, Wallet currentWallet) {
        this(storage, currentWallet, true);
    }

    public WalletForm(Storage storage, Wallet currentWallet, boolean refreshHistory) {
        this.storage = storage;
        this.wallet = currentWallet;

        refreshNodesSubject = PublishSubject.create();
        refreshNodesSubject.buffer(1, TimeUnit.SECONDS)
                .filter(walletNodes -> !walletNodes.isEmpty())
                .observeOn(JavaFxScheduler.platform())
                .subscribe(walletNodes -> {
                    refreshHistory(AppServices.getCurrentBlockHeight(), new HashSet<>(walletNodes));
                }, exception -> {
                    log.error("Error refreshing nodes", exception);
                });

        if(refreshHistory && wallet.isValid()) {
            refreshHistory(AppServices.getCurrentBlockHeight());
        }
    }

    public Wallet getWallet() {
        return wallet;
    }

    public Wallet getMasterWallet() {
        return wallet.isMasterWallet() ? wallet : wallet.getMasterWallet();
    }

    public String getMasterWalletId() {
        return storage.getWalletId(getMasterWallet());
    }

    public Storage getStorage() {
        return storage;
    }

    public String getWalletId() {
        return storage.getWalletId(wallet);
    }

    public File getWalletFile() {
        return storage.getWalletFile();
    }

    public void setWallet(Wallet wallet) {
        throw new UnsupportedOperationException("Only SettingsWalletForm supports setWallet");
    }

    public List<WalletForm> getNestedWalletForms() {
        return nestedWalletForms;
    }

    public void revert() {
        throw new UnsupportedOperationException("Only SettingsWalletForm supports revert");
    }

    public void save() throws IOException, StorageException {
        storage.saveWallet(wallet);
    }

    public void saveAndRefresh() throws IOException, StorageException {
        wallet.clearHistory();
        save();
        refreshHistory(AppServices.getCurrentBlockHeight());
    }

    public void saveBackup() throws IOException {
        storage.backupWallet();
    }

    protected void backgroundUpdate() {
        try {
            storage.updateWallet(wallet);
        } catch (IOException | StorageException e) {
            //Background save failed
            log.error("Background wallet save failed", e);
        }
    }

    public void deleteBackups() {
        storage.deleteBackups();
    }

    public void refreshHistory(Integer blockHeight) {
        refreshHistory(blockHeight, null, null);
    }

    public void refreshHistory(Integer blockHeight, Set<WalletNode> nodes) {
        refreshHistory(blockHeight, null, nodes);
    }

    public void refreshHistory(Integer blockHeight, List<Wallet> filterToWallets, Set<WalletNode> nodes) {
        Wallet previousWallet = wallet.copy();
        if(wallet.isValid() && AppServices.isConnected()) {
            if(log.isDebugEnabled()) {
                log.debug(nodes == null ? wallet.getFullName() + " refreshing full wallet history" : wallet.getFullName() + " requesting node wallet history for " + nodeRangesToString(nodes));
            }

            Set<WalletNode> walletTransactionNodes = getWalletTransactionNodes(nodes);
            if(!wallet.isNested() && (walletTransactionNodes == null || !walletTransactionNodes.isEmpty())) {
                ElectrumServer.TransactionHistoryService historyService = new ElectrumServer.TransactionHistoryService(wallet, filterToWallets, walletTransactionNodes);
                historyService.setOnSucceeded(workerStateEvent -> {
                    if(historyService.getValue()) {
                        EventManager.get().post(new WalletHistoryFinishedEvent(wallet));
                        updateWallets(blockHeight, previousWallet);
                    }
                });
                historyService.setOnFailed(workerStateEvent -> {
                    if(workerStateEvent.getSource().getException() instanceof AllHistoryChangedException) {
                        try {
                            storage.backupWallet();
                        } catch(IOException e) {
                            log.error("Error backing up wallet", e);
                        }

                        wallet.clearHistory();
                        AppServices.clearTransactionHistoryCache(wallet);
                        EventManager.get().post(new WalletHistoryClearedEvent(wallet, previousWallet, getWalletId()));
                    } else {
                        if(AppServices.isConnected()) {
                            log.error("Error retrieving wallet history", workerStateEvent.getSource().getException());
                        } else {
                            log.debug("Disconnected while retrieving wallet history", workerStateEvent.getSource().getException());
                        }

                        EventManager.get().post(new WalletHistoryFailedEvent(wallet, workerStateEvent.getSource().getException()));
                    }
                });

                EventManager.get().post(new WalletHistoryStartedEvent(wallet, nodes));
                historyService.start();
            }

            if(wallet.isMasterWallet() && wallet.hasPaymentCode() && refreshNotificationNode(nodes)) {
                ElectrumServer.PaymentCodesService paymentCodesService = new ElectrumServer.PaymentCodesService(getWalletId(), wallet);
                paymentCodesService.setOnSucceeded(successEvent -> {
                    List<Wallet> addedWallets = paymentCodesService.getValue();
                    for(Wallet addedWallet : addedWallets) {
                        if(!storage.isPersisted(addedWallet)) {
                            try {
                                storage.saveWallet(addedWallet);
                            } catch(Exception e) {
                                log.error("Error saving wallet", e);
                                AppServices.showErrorDialog("Error saving wallet " + addedWallet.getName(), e.getMessage());
                            }
                        }
                    }
                    if(!addedWallets.isEmpty()) {
                        EventManager.get().post(new ChildWalletsAddedEvent(storage, wallet, addedWallets));
                    }
                });
                paymentCodesService.setOnFailed(failedEvent -> {
                    log.error("Could not determine payment codes for wallet " + wallet.getFullName(), failedEvent.getSource().getException());
                });
                paymentCodesService.start();
            }
        }
    }

    private void updateWallets(Integer blockHeight, Wallet previousWallet) {
        List<WalletNode> nestedHistoryChangedNodes = new ArrayList<>();
        for(Wallet childWallet : new ArrayList<>(wallet.getChildWallets())) {
            if(childWallet.isNested()) {
                Wallet previousChildWallet = previousWallet.getChildWallet(childWallet.getName());
                if(previousChildWallet != null) {
                    nestedHistoryChangedNodes.addAll(updateWallet(blockHeight, childWallet, previousChildWallet, Collections.emptyList()));
                }
            }
        }

        updateWallet(blockHeight, wallet, previousWallet, nestedHistoryChangedNodes);
    }

    private List<WalletNode> updateWallet(Integer blockHeight, Wallet currentWallet, Wallet previousWallet, List<WalletNode> nestedHistoryChangedNodes) {
        if(blockHeight != null) {
            currentWallet.setStoredBlockHeight(blockHeight);
        }

        return notifyIfChanged(blockHeight, currentWallet, previousWallet, nestedHistoryChangedNodes);
    }

    private List<WalletNode> notifyIfChanged(Integer blockHeight, Wallet currentWallet, Wallet previousWallet, List<WalletNode> nestedHistoryChangedNodes) {
        List<WalletNode> historyChangedNodes = new ArrayList<>();
        historyChangedNodes.addAll(getHistoryChangedNodes(previousWallet.getNode(KeyPurpose.RECEIVE).getChildren(), currentWallet.getNode(KeyPurpose.RECEIVE).getChildren()));
        historyChangedNodes.addAll(getHistoryChangedNodes(previousWallet.getNode(KeyPurpose.CHANGE).getChildren(), currentWallet.getNode(KeyPurpose.CHANGE).getChildren()));

        boolean changed = false;
        if(!historyChangedNodes.isEmpty() || !nestedHistoryChangedNodes.isEmpty()) {
            Platform.runLater(() -> EventManager.get().post(new WalletHistoryChangedEvent(currentWallet, storage, historyChangedNodes, nestedHistoryChangedNodes)));
            if(!historyChangedNodes.isEmpty()) {
                changed = true;
            }
        }

        if(blockHeight != null && !blockHeight.equals(previousWallet.getStoredBlockHeight())) {
            Platform.runLater(() -> EventManager.get().post(new WalletBlockHeightChangedEvent(currentWallet, blockHeight)));
            changed = true;
        }

        if(changed) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(currentWallet)));
        }

        return historyChangedNodes;
    }

    private List<WalletNode> getHistoryChangedNodes(Set<WalletNode> previousNodes, Set<WalletNode> currentNodes) {
        Map<String, WalletNode> previousNodeMap = new HashMap<>(previousNodes.size());
        previousNodes.forEach(walletNode -> previousNodeMap.put(walletNode.getDerivationPath(), walletNode));

        List<WalletNode> changedNodes = new ArrayList<>();
        for(WalletNode currentNode : currentNodes) {
            WalletNode previousNode = previousNodeMap.get(currentNode.getDerivationPath());
            if(previousNode != null) {
                if(!currentNode.getTransactionOutputs().equals(previousNode.getTransactionOutputs())) {
                    changedNodes.add(currentNode);
                }
            } else {
                changedNodes.add(currentNode);
            }
        }

        return changedNodes;
    }

    public void addWalletTransactionNodes(Set<WalletNode> transactionNodes) {
        walletTransactionNodes.add(transactionNodes);
    }

    private Set<WalletNode> getWalletTransactionNodes(Set<WalletNode> walletNodes) {
        if(walletNodes == null) {
            return null;
        }

        Set<WalletNode> allNodes = new LinkedHashSet<>(walletNodes);
        for(WalletNode walletNode : walletNodes) {
            for(Set<WalletNode> nodes : walletTransactionNodes) {
                if(nodes.contains(walletNode)) {
                    allNodes.addAll(nodes);
                }
            }
        }

        Set<WalletNode> nodes = allNodes.isEmpty() ? walletNodes : allNodes;
        if(nodes.stream().anyMatch(node -> node.getDerivation().size() == 1)) {
            return nodes.stream().filter(node -> node.getDerivation().size() > 1).collect(Collectors.toSet());
        }

        return nodes;
    }

    public boolean refreshNotificationNode(Set<WalletNode> walletNodes) {
        if(walletNodes == null) {
            return true;
        }

        return walletNodes.stream().anyMatch(node -> node.getDerivation().size() == 1);
    }

    public WalletTransaction getCreatedWalletTransaction() {
        return createdWalletTransactionProperty.get();
    }

    public void setCreatedWalletTransaction(WalletTransaction createdWalletTransaction) {
        this.createdWalletTransactionProperty.set(createdWalletTransaction);
    }

    public NodeEntry getNodeEntry(KeyPurpose keyPurpose) {
        NodeEntry purposeEntry;
        Optional<NodeEntry> optionalPurposeEntry = accountEntries.stream().filter(entry -> entry.getNode().getKeyPurpose().equals(keyPurpose)).findFirst();
        if(optionalPurposeEntry.isPresent()) {
            purposeEntry = optionalPurposeEntry.get();
        } else {
            WalletNode purposeNode = getWallet().getNode(keyPurpose);
            purposeEntry = new NodeEntry(getWallet(), purposeNode);
            accountEntries.add(purposeEntry);
        }

        return purposeEntry;
    }

    public NodeEntry getFreshNodeEntry(KeyPurpose keyPurpose, NodeEntry currentEntry) {
        NodeEntry rootEntry = getNodeEntry(keyPurpose);
        WalletNode freshNode = getWallet().getFreshNode(keyPurpose, currentEntry == null ? null : currentEntry.getNode());

        for(Entry childEntry : rootEntry.getChildren()) {
            NodeEntry nodeEntry = (NodeEntry)childEntry;
            if(nodeEntry.getNode().equals(freshNode)) {
                return nodeEntry;
            }
        }

        NodeEntry freshEntry = new NodeEntry(getWallet(), freshNode);
        rootEntry.getChildren().add(freshEntry);
        return freshEntry;
    }

    public WalletTransactionsEntry getWalletTransactionsEntry() {
        if(walletTransactionsEntry == null) {
            walletTransactionsEntry = new WalletTransactionsEntry(wallet);
        }

        return walletTransactionsEntry;
    }

    public WalletUtxosEntry getWalletUtxosEntry() {
        if(walletUtxosEntry == null) {
            walletUtxosEntry = new WalletUtxosEntry(wallet);
        }

        return walletUtxosEntry;
    }

    public boolean isLocked() {
        return lockedProperty.get();
    }

    public BooleanProperty lockedProperty() {
        return lockedProperty;
    }

    public void setLocked(boolean locked) {
        this.lockedProperty.set(locked);
    }

    public List<NodeEntry> getAccountEntries() {
        return accountEntries;
    }

    @Subscribe
    public void walletDataChanged(WalletDataChangedEvent event) {
        if(event.getWallet().equals(wallet)) {
            backgroundUpdate();
        }
    }

    @Subscribe
    public void walletHistoryCleared(WalletHistoryClearedEvent event) {
        if(event.getWalletId().equals(getWalletId())) {
            //Replacing the WalletForm's wallet here is only possible because we immediately clear all derived structures and do a full wallet refresh
            wallet = event.getWallet();

            walletTransactionsEntry = null;
            walletUtxosEntry = null;
            accountEntries.clear();
            EventManager.get().post(new WalletNodesChangedEvent(wallet));

            //Clear the cache - we will need to fetch everything again
            AppServices.clearTransactionHistoryCache(wallet);
            refreshHistory(AppServices.getCurrentBlockHeight());
        }
    }

    @Subscribe
    public void keystoreLabelsChanged(KeystoreLabelsChangedEvent event) {
        if(event.getWalletId().equals(getWalletId())) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletWatchLastChanged(WalletWatchLastChangedEvent event) {
        if(event.getWalletId().equals(getWalletId())) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void keystoreEncryptionChanged(KeystoreEncryptionChangedEvent event) {
        if(event.getWalletId().equals(getWalletId())) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletPasswordChanged(WalletPasswordChangedEvent event) {
        if(event.getWalletId().equals(getWalletId())) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void newBlock(NewBlockEvent event) {
        //Check if wallet is valid to avoid saving wallets in initial setup
        if(wallet.isValid()) {
            updateWallet(event.getHeight(), wallet, wallet.copy(), Collections.emptyList());
        }
    }

    @Subscribe
    public void connected(ConnectionEvent event) {
        refreshHistory(event.getBlockHeight());
    }

    @Subscribe
    public void walletNodeHistoryChanged(WalletNodeHistoryChangedEvent event) {
        if(wallet.isValid() && !wallet.isNested()) {
            if(transactionMempoolService != null) {
                transactionMempoolService.cancel();
            }

            WalletNode walletNode = event.getWalletNode(wallet);
            if(walletNode != null) {
                log.debug(wallet.getFullName() + " history event for node " + walletNode + " (" + event.getScriptHash() + ")");
                refreshNodesSubject.onNext(walletNode);
            }
        }
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if(event.getWalletId().equals(getWalletId())) {
            for(WalletNode changedNode : event.getHistoryChangedNodes()) {
                if(changedNode.getLabel() != null && !changedNode.getLabel().isEmpty()) {
                    List<Entry> changedLabelEntries = new ArrayList<>();
                    for(BlockTransactionHashIndex receivedRef : changedNode.getTransactionOutputs()) {
                        BlockTransaction blockTransaction = wallet.getTransactions().get(receivedRef.getHash());
                        if(blockTransaction != null && (blockTransaction.getLabel() == null || blockTransaction.getLabel().isEmpty())) {
                            blockTransaction.setLabel(changedNode.getLabel());
                            changedLabelEntries.add(new TransactionEntry(event.getWallet(), blockTransaction, Collections.emptyMap(), Collections.emptyMap()));
                        }

                        if((receivedRef.getLabel() == null || receivedRef.getLabel().isEmpty()) && wallet.getStandardAccountType() != StandardAccount.WHIRLPOOL_PREMIX) {
                            receivedRef.setLabel(changedNode.getLabel() + (changedNode.getKeyPurpose() == KeyPurpose.CHANGE ? (changedNode.getWallet().isBip47() ? " (sent)" : " (change)") : " (received)"));
                            changedLabelEntries.add(new HashIndexEntry(event.getWallet(), receivedRef, HashIndexEntry.Type.OUTPUT, changedNode.getKeyPurpose()));
                        }
                    }

                    if(!changedLabelEntries.isEmpty()) {
                        Platform.runLater(() -> EventManager.get().post(new WalletEntryLabelsChangedEvent(event.getWallet(), changedLabelEntries)));
                    }
                }
            }
        }
    }

    @Subscribe
    public void walletLabelsChanged(WalletEntryLabelsChangedEvent event) {
        if(event.toThisOrNested(wallet)) {
            Map<Entry, Entry> labelChangedEntries = new LinkedHashMap<>();
            for(Entry entry : event.getEntries()) {
                if(entry.getLabel() != null && !entry.getLabel().isEmpty()) {
                    if(entry instanceof TransactionEntry transactionEntry) {
                        for(KeyPurpose keyPurpose : KeyPurpose.DEFAULT_PURPOSES) {
                            for(WalletNode childNode : wallet.getNode(keyPurpose).getChildren()) {
                                for(BlockTransactionHashIndex receivedRef : childNode.getTransactionOutputs()) {
                                    if(receivedRef.getHash().equals(transactionEntry.getBlockTransaction().getHash())) {
                                        if((receivedRef.getLabel() == null || receivedRef.getLabel().isEmpty()) && wallet.getStandardAccountType() != StandardAccount.WHIRLPOOL_PREMIX) {
                                            receivedRef.setLabel(entry.getLabel() + (keyPurpose == KeyPurpose.CHANGE ? (event.getWallet().isBip47() ? " (sent)" : " (change)") : " (received)"));
                                            labelChangedEntries.put(new HashIndexEntry(event.getWallet(), receivedRef, HashIndexEntry.Type.OUTPUT, keyPurpose), entry);
                                        }
                                        if((childNode.getLabel() == null || childNode.getLabel().isEmpty())) {
                                            childNode.setLabel(entry.getLabel());
                                            labelChangedEntries.put(new NodeEntry(event.getWallet(), childNode), entry);
                                        }
                                    }
                                    if(receivedRef.isSpent() && receivedRef.getSpentBy().getHash().equals(transactionEntry.getBlockTransaction().getHash()) && (receivedRef.getSpentBy().getLabel() == null || receivedRef.getSpentBy().getLabel().isEmpty())) {
                                        receivedRef.getSpentBy().setLabel(entry.getLabel() + " (input)");
                                        labelChangedEntries.put(new HashIndexEntry(event.getWallet(), receivedRef.getSpentBy(), HashIndexEntry.Type.INPUT, keyPurpose), entry);
                                    }
                                }
                            }
                        }
                    }
                    if(entry instanceof NodeEntry nodeEntry) {
                        for(BlockTransactionHashIndex receivedRef : nodeEntry.getNode().getTransactionOutputs()) {
                            BlockTransaction blockTransaction = event.getWallet().getTransactions().get(receivedRef.getHash());
                            if(blockTransaction.getLabel() == null || blockTransaction.getLabel().isEmpty()) {
                                blockTransaction.setLabel(entry.getLabel());
                                labelChangedEntries.put(new TransactionEntry(event.getWallet(), blockTransaction, Collections.emptyMap(), Collections.emptyMap()), entry);
                            }
                        }
                    }
                    if(entry instanceof HashIndexEntry hashIndexEntry) {
                        BlockTransaction blockTransaction = hashIndexEntry.getBlockTransaction();
                        //Avoid recursive changes from hashIndexEntries
                        if((blockTransaction.getLabel() == null || blockTransaction.getLabel().isEmpty()) && event.getSource(entry) == null) {
                            blockTransaction.setLabel(entry.getLabel());
                            labelChangedEntries.put(new TransactionEntry(event.getWallet(), blockTransaction, Collections.emptyMap(), Collections.emptyMap()), entry);
                        }
                    }
                }
            }

            if(!labelChangedEntries.isEmpty()) {
                Platform.runLater(() -> EventManager.get().post(new WalletEntryLabelsChangedEvent(wallet, labelChangedEntries)));
            } else {
                Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
            }
        }
    }

    @Subscribe
    public void walletDeleted(WalletDeletedEvent event) {
        if(event.getWallet() == wallet && !wallet.isMasterWallet()) {
            wallet.getMasterWallet().getChildWallets().remove(wallet);
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletUtxoStatusChanged(WalletUtxoStatusChangedEvent event) {
        if(event.getWallet() == wallet) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletConfigChanged(WalletConfigChangedEvent event) {
        if(event.getWallet() == wallet) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletMixConfigChanged(WalletMixConfigChangedEvent event) {
        if(event.getWallet() == wallet) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletUtxoMixesChanged(WalletUtxoMixesChangedEvent event) {
        if(event.getWallet() == wallet) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletLabelChanged(WalletLabelChangedEvent event) {
        if(event.getWallet() == wallet) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));
        }
    }

    @Subscribe
    public void walletGapLimitChanged(WalletGapLimitChangedEvent event) {
        if(event.getWallet() == wallet) {
            Platform.runLater(() -> EventManager.get().post(new WalletDataChangedEvent(wallet)));

            Set<WalletNode> newNodes = new LinkedHashSet<>();
            for(KeyPurpose keyPurpose : KeyPurpose.DEFAULT_PURPOSES) {
                Optional<WalletNode> optPurposeNode = wallet.getPurposeNodes().stream().filter(node -> node.getKeyPurpose() == keyPurpose).findFirst();
                if(optPurposeNode.isPresent()) {
                    WalletNode purposeNode = optPurposeNode.get();
                    newNodes.addAll(purposeNode.fillToIndex(wallet, wallet.getLookAheadIndex(purposeNode)));
                }
            }

            if(!newNodes.isEmpty()) {
                Platform.runLater(() -> refreshHistory(AppServices.getCurrentBlockHeight(), newNodes));
            }
        }
    }

    @Subscribe
    public void whirlpoolMixSuccess(WhirlpoolMixSuccessEvent event) {
        if(event.getWallet() == wallet && event.getWalletNode() != null) {
            if(transactionMempoolService != null) {
                transactionMempoolService.cancel();
            }

            transactionMempoolService = new ElectrumServer.TransactionMempoolService(event.getWallet(), Sha256Hash.wrap(event.getNextUtxo().getHash()), Set.of(event.getWalletNode()));
            transactionMempoolService.setDelay(Duration.seconds(5));
            transactionMempoolService.setPeriod(Duration.seconds(5));
            transactionMempoolService.setRestartOnFailure(false);
            transactionMempoolService.setOnSucceeded(mempoolWorkerStateEvent -> {
                Set<String> scriptHashes = transactionMempoolService.getValue();
                if(!scriptHashes.isEmpty()) {
                    Platform.runLater(() -> EventManager.get().post(new WalletNodeHistoryChangedEvent(scriptHashes.iterator().next())));
                }

                if(transactionMempoolService.getIterationCount() > 10) {
                    transactionMempoolService.cancel();
                }
            });
            transactionMempoolService.start();
        }
    }

    @Subscribe
    public void walletTabsClosed(WalletTabsClosedEvent event) {
        for(WalletTabData tabData : event.getClosedWalletTabData()) {
            if(tabData.getWalletForm() == this) {
                if(wallet.isMasterWallet()) {
                    storage.close();
                }
                if(wallet.isValid()) {
                    AppServices.clearTransactionHistoryCache(wallet);
                }
                EventManager.get().unregister(this);
                for(WalletForm nestedWalletForm : nestedWalletForms) {
                    EventManager.get().unregister(nestedWalletForm);
                }
            }
        }
    }

    @Subscribe
    public void hideEmptyUsedAddressesStatusChanged(HideEmptyUsedAddressesStatusEvent event) {
        accountEntries.clear();
        EventManager.get().post(new WalletAddressesStatusEvent(wallet));
    }

    @Subscribe
    public void childWalletsAdded(ChildWalletsAddedEvent event) {
        if(event.getWallet() == wallet) {
            List<Wallet> nestedWallets = event.getChildWallets().stream().filter(Wallet::isNested).collect(Collectors.toList());
            if(!nestedWallets.isEmpty()) {
                Platform.runLater(() -> refreshHistory(AppServices.getCurrentBlockHeight(), nestedWallets, null));
            }
        }
    }

    @Subscribe
    public void payNymImageLoaded(PayNymImageLoadedEvent event) {
        if(wallet.isMasterWallet() && wallet.hasPaymentCode() && event.getPaymentCode().equals(wallet.getPaymentCode())) {
            WalletConfig walletConfig = wallet.getMasterWalletConfig();
            if(!walletConfig.isUserIcon()) {
                byte[] iconData = ImageUtils.resize(event.getImage(), WalletIcon.SAVE_WIDTH, WalletIcon.SAVE_HEIGHT);
                if(walletConfig.getIconData() == null || !Arrays.equals(walletConfig.getIconData(), iconData)) {
                    walletConfig.setIconData(iconData, false);
                    EventManager.get().post(new WalletConfigChangedEvent(wallet));
                }
            }
        }
    }
}
