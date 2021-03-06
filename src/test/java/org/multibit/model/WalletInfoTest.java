/**
 * Copyright 2012 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multibit.model;

import java.io.File;
import java.util.ArrayList;

import junit.framework.TestCase;

import org.junit.Test;
import org.multibit.Constants;
import org.multibit.Localiser;
import org.multibit.controller.MultiBitController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WalletInfoTest extends TestCase {

    private static final Logger log = LoggerFactory.getLogger(WalletInfoTest.class);

    public static final String WALLET_TESTDATA_DIRECTORY = "wallets";

    public static final String WALLET_TEST1 = "walletInfoTest.wallet";
    public static final String DESCRIPTION_TEST1 = "myDescription1, textAfterComma\ntextAfterReturn";

    public static final String NON_EXISTENT_WALLET = "nonExistentWallet.wallet";

    public static final String EXAMPLE_RECEIVING_ADDRESS = "1NzESHfiazCbxwhTCg2jiTWcZgpSMKDKhy";
    public static final String EXAMPLE_RECEIVING_ADDRESS_LABEL = "myReceivingAddress label, text after comma\n text after return";

    public static final String EXAMPLE_SENDING_ADDRESS = "1K9A6xh9wGZD1xNLBdxUSFNxtRFUsw5Z4n";
    public static final String EXAMPLE_SENDING_ADDRESS_LABEL = "mySendingAddress label, text after comma\n text after return";

    @Test
    public void testRoundTrip() throws WalletInfoException {
        // set up core objects
        MultiBitController controller = new MultiBitController();
        Localiser localiser = new Localiser();
        MultiBitModel model = new MultiBitModel(controller);

        controller.setLocaliser(localiser);
        controller.setModel(model);

        // get test directory and wallet
        File directory = new File(".");
        String currentPath = directory.getAbsolutePath();

        String walletName = currentPath + File.separator + Constants.TESTDATA_DIRECTORY + File.separator
                + WALLET_TESTDATA_DIRECTORY + File.separator + WALLET_TEST1;

        // create wallet info
        WalletInfo walletInfo = new WalletInfo(walletName);
        assertNotNull(walletInfo);

        walletInfo.put(WalletInfo.DESCRIPTION_PROPERTY, DESCRIPTION_TEST1);

        AddressBookData receivingAddress = new AddressBookData(EXAMPLE_RECEIVING_ADDRESS_LABEL, EXAMPLE_RECEIVING_ADDRESS);
        walletInfo.addReceivingAddress(receivingAddress, false);

        AddressBookData sendingAddress = new AddressBookData(EXAMPLE_SENDING_ADDRESS_LABEL, EXAMPLE_SENDING_ADDRESS);
        walletInfo.addSendingAddress(sendingAddress);

        // write to file
        walletInfo.writeToFile();

        String createdWalletInfoFile = WalletInfo.createWalletInfoFilename(walletName);

        assertTrue((new File(createdWalletInfoFile)).exists());

        // create new wallet info and reload
        WalletInfo rebornWalletInfo = new WalletInfo(walletName);
        assertNotNull(rebornWalletInfo);

        // check description
        assertEquals(DESCRIPTION_TEST1, rebornWalletInfo.getProperty(WalletInfo.DESCRIPTION_PROPERTY));

        // check sending address
        ArrayList<AddressBookData> sendAddresses = rebornWalletInfo.getSendingAddresses();
        assertEquals(1, sendAddresses.size());
        AddressBookData sendAddress = sendAddresses.get(0);
        assertEquals(EXAMPLE_SENDING_ADDRESS_LABEL, sendAddress.getLabel());
        assertEquals(EXAMPLE_SENDING_ADDRESS, sendAddress.getAddress());
 
        // check receiving address
        ArrayList<AddressBookData> receiveAddresses = rebornWalletInfo.getReceivingAddresses();
        assertEquals(1, receiveAddresses.size());
        AddressBookData receiveAddress = receiveAddresses.get(0);
        assertEquals(EXAMPLE_RECEIVING_ADDRESS_LABEL, receiveAddress.getLabel());
        assertEquals(EXAMPLE_RECEIVING_ADDRESS, receiveAddress.getAddress());
    }

    @Test
    public void testloadNonExistentInfoFile() throws WalletInfoException {
        // set up core objects
        MultiBitController controller = new MultiBitController();
        Localiser localiser = new Localiser();
        MultiBitModel model = new MultiBitModel(controller);

        controller.setLocaliser(localiser);
        controller.setModel(model);

        // get test directory and wallet
        File directory = new File(".");
        String currentPath = directory.getAbsolutePath();

        String walletName = currentPath + File.separator + Constants.TESTDATA_DIRECTORY + File.separator
                + WALLET_TESTDATA_DIRECTORY + File.separator + NON_EXISTENT_WALLET;

        // create wallet info - should not through exception
        WalletInfo walletInfo = new WalletInfo(walletName);
        assertNotNull(walletInfo);
    }
    
    @Test
    public void testURLEncodeDecode() {
        String initialText = "abcdefghijklmnopqrstuvwxyz%201234567890 !@#$%^&*()_+-= []{};':|`~,./<>?";
        String encodedText = WalletInfo.encodeURLString(initialText);
        String decodedText = WalletInfo.decodeURLString(encodedText);
        
        assertEquals(initialText, decodedText);
        
        decodedText = WalletInfo.decodeURLString("%20abcdef");
        assertEquals(" abcdef", decodedText);

        // checking passing unencoded
        decodedText = WalletInfo.decodeURLString("abc% de+f, jb\n etc");
        assertEquals("abc% de+f, jb\n etc", decodedText);

        decodedText = WalletInfo.decodeURLString("abc def, jb\n etc");
        assertEquals("abc def, jb\n etc", decodedText);

        // checking percent character
        decodedText = WalletInfo.decodeURLString("abc%");
        assertEquals("abc%", decodedText);

        decodedText = WalletInfo.decodeURLString("abc%d");
        assertEquals("abc%d", decodedText);
    }
}
