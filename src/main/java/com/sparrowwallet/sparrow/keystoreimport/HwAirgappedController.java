package com.sparrowwallet.sparrow.keystoreimport;

import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.sparrow.control.FileKeystoreImportPane;
import com.sparrowwallet.sparrow.io.*;
import javafx.fxml.FXML;
import javafx.scene.control.Accordion;

import java.util.Collections;
import java.util.List;

public class HwAirgappedController extends KeystoreImportDetailController {
    @FXML
    private Accordion importAccordion;

    public void initializeView() {
        List<KeystoreFileImport> importers = Collections.emptyList();
        if(getMasterController().getWallet().getPolicyType().equals(PolicyType.SINGLE)) {
            importers = List.of(new ColdcardSinglesig(), new CoboVaultSinglesig());
        } else if(getMasterController().getWallet().getPolicyType().equals(PolicyType.MULTI)) {
            importers = List.of(new ColdcardMultisig(), new CoboVaultMultisig());
        }

        for(KeystoreImport importer : importers) {
            FileKeystoreImportPane importPane = new FileKeystoreImportPane(getMasterController().getWallet(), (KeystoreFileImport)importer);;
            importAccordion.getPanes().add(importPane);
        }
    }
}
