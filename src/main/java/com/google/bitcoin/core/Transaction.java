/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.core;

import static com.google.bitcoin.core.Utils.bitcoinValueToPlainString;
import static com.google.bitcoin.core.Utils.doubleDigest;
import static com.google.bitcoin.core.Utils.reverseBytes;
import static com.google.bitcoin.core.Utils.uint32ToByteStreamLE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.multibit.IsMultiBitClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A transaction represents the movement of coins from some addresses to some other addresses. It can also represent
 * the minting of new coins. A Transaction object corresponds to the equivalent in the BitCoin C++ implementation.<p>
 *
 * It implements TWO serialization protocols - the BitCoin proprietary format which is identical to the C++
 * implementation and is used for reading/writing transactions to the wire and for hashing. It also implements Java
 * serialization which is used for the wallet. This allows us to easily add extra fields used for our own accounting
 * or UI purposes.<p>
 *     
 * All Bitcoin transactions are at risk of being reversed, though the risk is much less than with traditional payment 
 * systems. Transactions have <i>confidence levels</i>, which help you decide whether to trust a transaction or not.
 * Whether to trust a transaction is something that needs to be decided on a case by case basis - a rule that makes 
 * sense for selling MP3s might not make sense for selling cars, or accepting payments from a family member. If you
 * are building a wallet, how to present confidence to your users is something to consider carefully.
 */
public class Transaction extends ChildMessage implements Serializable, IsMultiBitClass {
    private static final Logger log = LoggerFactory.getLogger(Transaction.class);
    private static final long serialVersionUID = -8567546957352643140L;

    // These are serialized in both bitcoin and java serialization.
    private long version;
    private ArrayList<TransactionInput> inputs;
    //a cached copy to prevent constantly rewrapping
    //private transient List<TransactionInput> immutableInputs;

    private ArrayList<TransactionOutput> outputs;
    //a cached copy to prevent constantly rewrapping
    //private transient List<TransactionOutput> immutableOutputs;

    private long lockTime;

    // This is being migrated to appearsInHashes. It's set to null after migration.
    Set<StoredBlock> appearsIn;
    
    // Stored only in Java serialization. This is either the time the transaction was broadcast as measured from the
    // local clock, or the time from the block in which it was included. Note that this can be changed by re-orgs so
    // the wallet may update this field. Old serialized transactions don't have this field, thus null is valid.
    // It is used for returning an ordered list of transactions from a wallet, which is helpful for presenting to
    // users.
    Date updatedAt;

    // This is an in memory helper only.
    transient Sha256Hash hash;
    
    // Data about how confirmed this tx is. Serialized, may be null. 
    private TransactionConfidence confidence;

    // This records which blocks the transaction
    // has been included in. For most transactions this set will have a single member. In the case of a chain split a
    // transaction may appear in multiple blocks but only one of them is part of the best chain. It's not valid to
    // have an identical transaction appear in two blocks in the same chain but this invariant is expensive to check,
    // so it's not directly enforced anywhere.
    //
    // If this transaction is not stored in the wallet, appearsInHashes is null.
    Set<Sha256Hash> appearsInHashes;

    public Transaction(NetworkParameters params) {
        super(params);
        version = 1;
        inputs = new ArrayList<TransactionInput>();
        outputs = new ArrayList<TransactionOutput>();
        // We don't initialize appearsIn deliberately as it's only useful for transactions stored in the wallet.
        length = 10; // 8 for std fields + 1 for each 0 varint

        updatedAt = new Date();
    }

    public Transaction(NetworkParameters params, int version, Sha256Hash hash) {
        super(params);
        this.version = version & ((1L<<32) - 1); // this field is unsigned - remove any sign extension
        inputs = new ArrayList<TransactionInput>();
        outputs = new ArrayList<TransactionOutput>();
        this.hash = hash;
        // We don't initialize appearsIn deliberately as it's only useful for transactions stored in the wallet.
        length = 10; //8 for std fields + 1 for each 0 varint
        
        updatedAt = new Date();
    }

    /**
     * Creates a transaction from the given serialized bytes, eg, from a block or a tx network message.
     */
    public Transaction(NetworkParameters params, byte[] payloadBytes) throws ProtocolException {
        super(params, payloadBytes, 0);
    }

    /**
     * Creates a transaction by reading payload starting from offset bytes in. Length of a transaction is fixed.
     */
    public Transaction(NetworkParameters params, byte[] payload, int offset) throws ProtocolException {
        super(params, payload, offset);
        // inputs/outputs will be created in parse()
    }

    /**
     * Creates a transaction by reading payload starting from offset bytes in. Length of a transaction is fixed.
     * @param params NetworkParameters object.
     * @param msg Bitcoin protocol formatted byte array containing message content.
     * @param offset The location of the first msg byte within the array.
     * @param parseLazy Whether to perform a full parse immediately or delay until a read is requested.
     * @param parseRetain Whether to retain the backing byte array for quick reserialization.  
     * If true and the backing byte array is invalidated due to modification of a field then 
     * the cached bytes may be repopulated and retained if the message is serialized again in the future.
     * @param length The length of message if known.  Usually this is provided when deserializing of the wire
     * as the length will be provided as part of the header.  If unknown then set to Message.UNKNOWN_LENGTH
     * @throws ProtocolException
     */
    public Transaction(NetworkParameters params, byte[] msg, int offset, Message parent, boolean parseLazy, boolean parseRetain, int length)
            throws ProtocolException {
        super(params, msg, offset, parent, parseLazy, parseRetain, length);
    }

    /**
     * Creates a transaction by reading payload starting from offset bytes in. Length of a transaction is fixed.
     */
    public Transaction(NetworkParameters params, byte[] msg, Message parent, boolean parseLazy, boolean parseRetain, int length)
            throws ProtocolException {
        super(params, msg, 0, parent, parseLazy, parseRetain, length);
    }

    /**
     * Returns the transaction hash as you see them in the block explorer.
     */
    public Sha256Hash getHash() {
        if (hash == null) {
            byte[] bits = bitcoinSerialize();
            hash = new Sha256Hash(reverseBytes(doubleDigest(bits)));
        }
        return hash;
    }

    /**
     * Used by BitcoinSerializer.  The serializer has to calculate a hash for checksumming so to
     * avoid wasting the considerable effort a set method is provided so the serializer can set it.
	 *
     * No verification is performed on this hash.
     */
    void setHash(Sha256Hash hash) {
        this.hash = hash;
    }

    public String getHashAsString() {
        return getHash().toString();
    }

    /**
     * Calculates the sum of the outputs that are sending coins to a key in the wallet. The flag controls whether to
     * include spent outputs or not.
     */
    BigInteger getValueSentToMe(Wallet wallet, boolean includeSpent) {
        maybeParse();
        // This is tested in WalletTest.
        BigInteger v = BigInteger.ZERO;
        for (TransactionOutput o : outputs) {
            if (!o.isMine(wallet)) continue;
            if (!includeSpent && !o.isAvailableForSpending()) continue;
            v = v.add(o.getValue());
        }
        return v;
    }

    /**
     * Calculates the sum of the outputs that are sending coins to a key in the wallet.
     */
    public BigInteger getValueSentToMe(Wallet wallet) {
        return getValueSentToMe(wallet, true);
    }

    /**
     * Returns a set of blocks which contain the transaction, or null if this transaction doesn't have that data
     * because it's not stored in the wallet or because it has never appeared in a block.
     */
    public Collection<Sha256Hash> getAppearsInHashes() {
        if (appearsInHashes != null)
            return appearsInHashes;
        
        if (appearsIn != null) {
            assert appearsInHashes == null;
            log.info("Migrating a tx to appearsInHashes");
            appearsInHashes = new HashSet<Sha256Hash>(appearsIn.size());
            for (StoredBlock block : appearsIn) {
                appearsInHashes.add(block.getHeader().getHash());
            }
            appearsIn = null;
        }
        
        return appearsInHashes;
    }

    /**
     * Convenience wrapper around getConfidence().getConfidenceType()
     * @return true if this transaction hasn't been seen in any block yet.
     */
    public boolean isPending() {
        return getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.NOT_SEEN_IN_CHAIN;
    }

    /**
     * Puts the given block in the internal serializable set of blocks in which this transaction appears. This is
     * used by the wallet to ensure transactions that appear on side chains are recorded properly even though the
     * block stores do not save the transaction data at all.<p>
     *
     * <p>If there is a re-org this will be called once for each block that was previously seen, to update which block
     * is the best chain. The best chain block is guaranteed to be called last. So this must be idempotent.
     *
     * <p>Sets updatedAt to be the earliest valid block time where this tx was seen
     * 
     * @param block     The {@link StoredBlock} in which the transaction has appeared.
     * @param bestChain whether to set the updatedAt timestamp from the block header (only if not already set)
     */
    public void setBlockAppearance(StoredBlock block, boolean bestChain) {
        long blockTime = block.getHeader().getTimeSeconds() * 1000;
        if (bestChain && (updatedAt == null || updatedAt.getTime() == 0 || updatedAt.getTime() > blockTime)) {
            updatedAt = new Date(blockTime);
        }
        
        addBlockAppearance(block.getHeader().getHash());

        if (bestChain) {
            // This can cause event listeners on TransactionConfidence to run. After this line completes, the wallets
            // state may have changed!
            getConfidence().setAppearedAtChainHeight(block.getHeight());
        }
    }

    public void addBlockAppearance(final Sha256Hash blockHash) {
        if (appearsInHashes == null) {
            appearsInHashes = new HashSet<Sha256Hash>();
        }
        appearsInHashes.add(blockHash);
    }

    /** Called by the wallet once a re-org means we don't appear in the best chain anymore. */
    void notifyNotOnBestChain() {
        getConfidence().setConfidenceType(TransactionConfidence.ConfidenceType.NOT_IN_BEST_CHAIN);
    }

    /**
     * Calculates the sum of the inputs that are spending coins with keys in the wallet. This requires the
     * transactions sending coins to those keys to be in the wallet. This method will not attempt to download the
     * blocks containing the input transactions if the key is in the wallet but the transactions are not.
     *
     * @return sum in nanocoins.
     */
    public BigInteger getValueSentFromMe(Wallet wallet) throws ScriptException {
        maybeParse();
        // This is tested in WalletTest.
        BigInteger v = BigInteger.ZERO;
        for (TransactionInput input : inputs) {
            // This input is taking value from an transaction in our wallet. To discover the value,
            // we must find the connected transaction.
            TransactionOutput connected = input.getConnectedOutput(wallet.unspent);
            if (connected == null)
                connected = input.getConnectedOutput(wallet.spent);
            if (connected == null)
                connected = input.getConnectedOutput(wallet.pending);
            if (connected == null)
                continue;
            // The connected output may be the change to the sender of a previous input sent to this wallet. In this
            // case we ignore it.
            if (!connected.isMine(wallet))
                continue;
            v = v.add(connected.getValue());
        }
        return v;
    }

    /**
     * Returns the difference of {@link Transaction#getValueSentFromMe(Wallet)} and {@link Transaction#getValueSentToMe(Wallet)}.
     */
    public BigInteger getValue(Wallet wallet) throws ScriptException {
        return getValueSentToMe(wallet).subtract(getValueSentFromMe(wallet));
    }

    boolean disconnectInputs() {
        boolean disconnected = false;
        maybeParse();
        for (TransactionInput input : inputs) {
            disconnected |= input.disconnect();
        }
        return disconnected;
    }

    /**
     * Connects all inputs using the provided transactions. If any input cannot be connected returns that input or
     * null on success.
     */
    TransactionInput connectForReorganize(Map<Sha256Hash, Transaction> transactions) {
        maybeParse();
        for (TransactionInput input : inputs) {
            // Coinbase transactions, by definition, do not have connectable inputs.
            if (input.isCoinBase()) continue;
            TransactionInput.ConnectionResult result = input.connect(transactions, false);
            // Connected to another tx in the wallet?
            if (result == TransactionInput.ConnectionResult.SUCCESS)
                continue;
            // The input doesn't exist in the wallet, eg because it belongs to somebody else (inbound spend).
            if (result == TransactionInput.ConnectionResult.NO_SUCH_TX)
                continue;
            // Could not connect this input, so return it and abort.
            return input;
        }
        return null;
    }

    /**
     * Returns true if every output is marked as spent.
     */
    public boolean isEveryOutputSpent() {
        maybeParse();
        for (TransactionOutput output : outputs) {
            if (output.isAvailableForSpending())
                return false;
        }
        return true;
    }

    /**
     * Returns true if every output owned by the given wallet is spent.
     */
    public boolean isEveryOwnedOutputSpent(Wallet wallet) {
        maybeParse();
        for (TransactionOutput output : outputs) {
            if (output.isAvailableForSpending() && output.isMine(wallet))
                return false;
        }
        return true;
    }

    /**
     * Returns the earliest time at which the transaction was seen (broadcast or included into the chain),
     * or the epoch if that information isn't available.
     */
    public Date getUpdateTime() {
        if (updatedAt == null) {
            // Older wallets did not store this field. Set to the epoch.
            updatedAt = new Date(0);
        }
        return updatedAt;
    }
    
    public void setUpdateTime(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * These constants are a part of a scriptSig signature on the inputs. They define the details of how a
     * transaction can be redeemed, specifically, they control how the hash of the transaction is calculated.
     * <p/>
     * In the official client, this enum also has another flag, SIGHASH_ANYONECANPAY. In this implementation,
     * that's kept separate. Only SIGHASH_ALL is actually used in the official client today. The other flags
     * exist to allow for distributed contracts.
     */
    public enum SigHash {
        ALL,         // 1
        NONE,        // 2
        SINGLE,      // 3
    }

    protected void unCache() {
        super.unCache();
        hash = null;
    }

    protected void parseLite() throws ProtocolException {

        //skip this if the length has been provided i.e. the tx is not part of a block
        if (parseLazy && length == UNKNOWN_LENGTH) {
            //If length hasn't been provided this tx is probably contained within a block.
            //In parseRetain mode the block needs to know how long the transaction is
            //unfortunately this requires a fairly deep (though not total) parse.
            //This is due to the fact that transactions in the block's list do not include a
            //size header and inputs/outputs are also variable length due the contained
            //script so each must be instantiated so the scriptlength varint can be read
            //to calculate total length of the transaction.
            //We will still persist will this semi-light parsing because getting the lengths
            //of the various components gains us the ability to cache the backing bytearrays
            //so that only those subcomponents that have changed will need to be reserialized.

            //parse();
            //parsed = true;
            length = calcLength(bytes, cursor, offset);
            cursor = offset + length;
        }
    }

    protected static int calcLength(byte[] buf, int cursor, int offset) {
        VarInt varint;
        // jump past version (uint32)
        cursor = offset + 4;

        int i;
        long scriptLen;

        varint = new VarInt(buf, cursor);
        long txInCount = varint.value;
        cursor += varint.getSizeInBytes();

        for (i = 0; i < txInCount; i++) {
        	// 36 = length of previous_outpoint
            cursor += 36;
            varint = new VarInt(buf, cursor);
            scriptLen = varint.value;
            // 4 = length of sequence field (unint32)
            cursor += scriptLen + 4 + varint.getSizeInBytes();
        }

        varint = new VarInt(buf, cursor);
        long txOutCount = varint.value;
        cursor += varint.getSizeInBytes();

        for (i = 0; i < txOutCount; i++) {
            // 8 = length of tx value field (uint64)
        	cursor += 8;
            varint = new VarInt(buf, cursor);
            scriptLen = varint.value;
            cursor += scriptLen + varint.getSizeInBytes();
        }
        // 4 = length of lock_time field (uint32)
        return cursor - offset + 4;
    }

    void parse() throws ProtocolException {

        if (parsed)
            return;

        cursor = offset;

        version = readUint32();

        // First come the inputs.
        long numInputs = readVarInt();
        inputs = new ArrayList<TransactionInput>((int) numInputs);
        for (long i = 0; i < numInputs; i++) {
            TransactionInput input = new TransactionInput(params, this, bytes, cursor, parseLazy, parseRetain);
            inputs.add(input);
            cursor += input.getMessageSize();
        }
        // Now the outputs
        long numOutputs = readVarInt();
        outputs = new ArrayList<TransactionOutput>((int) numOutputs);
        for (long i = 0; i < numOutputs; i++) {
            TransactionOutput output = new TransactionOutput(params, this, bytes, cursor, parseLazy, parseRetain);
            outputs.add(output);
            cursor += output.getMessageSize();
        }
        lockTime = readUint32();
        length = cursor - offset;
    }

    /**
     * A coinbase transaction is one that creates a new coin. They are the first transaction in each block and their
     * value is determined by a formula that all implementations of BitCoin share. In 2011 the value of a coinbase
     * transaction is 50 coins, but in future it will be less. A coinbase transaction is defined not only by its
     * position in a block but by the data in the inputs.
     */
    public boolean isCoinBase() {
        maybeParse();
        return inputs.get(0).isCoinBase();
    }

    /**
     * @return A human readable version of the transaction useful for debugging.
     */
    public String toString() {
        StringBuffer s = new StringBuffer();
        s.append("  ");
        s.append(getHashAsString());
        s.append("\n");
        if (isCoinBase()) {
            String script;
            String script2;
            try {
                script = inputs.get(0).getScriptSig().toString();
                script2 = outputs.get(0).getScriptPubKey().toString();
            } catch (ScriptException e) {
                script = "???";
                script2 = "???";
            }
            return "     == COINBASE TXN (scriptSig " + script + ")  (scriptPubKey " + script2 + ")";
        }
        for (TransactionInput in : inputs) {
            s.append("     ");
            s.append("from ");

            try {
                s.append(in.getScriptSig().getFromAddress().toString());
                s.append(" / ");
                s.append(in.getOutpoint().toString());
            } catch (Exception e) {
                s.append("[exception: ").append(e.getMessage()).append("]");
            }
            s.append("\n");
        }
        for (TransactionOutput out : outputs) {
            s.append("       ");
            s.append("to ");
            try {
                Address toAddr = new Address(params, out.getScriptPubKey().getPubKeyHash());
                s.append(toAddr.toString());
                s.append(" ");
                s.append(bitcoinValueToPlainString(out.getValue()));
                s.append(" BTC");
                if (!out.isAvailableForSpending()) {
                    s.append(" Spent");
                }
                if (out.getSpentBy() != null) {
                    s.append(" by ");
                    s.append(out.getSpentBy().getParentTransaction().getHashAsString());
                }
            } catch (Exception e) {
                s.append("[exception: ").append(e.getMessage()).append("]");
            }
            s.append("\n");
        }
        return s.toString();
    }

    /**
     * Adds an input to this transaction that imports value from the given output. Note that this input is NOT
     * complete and after every input is added with addInput() and every output is added with addOutput(),
     * signInputs() must be called to finalize the transaction and finish the inputs off. Otherwise it won't be
     * accepted by the network.
     */
    public void addInput(TransactionOutput from) {
        addInput(new TransactionInput(params, this, from));
    }

    /**
     * Adds an input directly, with no checking that it's valid.
     */
    public void addInput(TransactionInput input) {
        unCache();
        input.setParent(this);
        inputs.add(input);
        adjustLength(input.length);
    }

    /**
     * Adds the given output to this transaction. The output must be completely initialized.
     */
    public void addOutput(TransactionOutput to) {
        unCache();

        //these could be merged into one but would need parentTransaction to be cast whenever it was accessed.
        to.setParent(this);
        to.parentTransaction = this;

        outputs.add(to);
        adjustLength(to.length);
    }

    /**
     * Creates an output based on the given address and value, adds it to this transaction.
     */
    public void addOutput(BigInteger value, Address address) {
        addOutput(new TransactionOutput(params, this, value, address));
    }

    /**
     * Once a transaction has some inputs and outputs added, the signatures in the inputs can be calculated. The
     * signature is over the transaction itself, to prove the redeemer actually created that transaction,
     * so we have to do this step last.<p>
     * <p/>
     * This method is similar to SignatureHash in script.cpp
     *
     * @param hashType This should always be set to SigHash.ALL currently. Other types are unused.
     * @param wallet   A wallet is required to fetch the keys needed for signing.
     */
    public void signInputs(SigHash hashType, Wallet wallet) throws ScriptException {
        assert inputs.size() > 0;
        assert outputs.size() > 0;

        // I don't currently have an easy way to test other modes work, as the official client does not use them.
        assert hashType == SigHash.ALL;

        // The transaction is signed with the input scripts empty except for the input we are signing. In the case
        // where addInput has been used to set up a new transaction, they are already all empty. The input being signed
        // has to have the connected OUTPUT program in it when the hash is calculated!
        //
        // Note that each input may be claiming an output sent to a different key. So we have to look at the outputs
        // to figure out which key to sign with.

        byte[][] signatures = new byte[inputs.size()][];
        ECKey[] signingKeys = new ECKey[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            TransactionInput input = inputs.get(i);
            assert input.getScriptBytes().length == 0 : "Attempting to sign a non-fresh transaction";
            // Set the input to the script of its output.
            input.setScriptBytes(input.getOutpoint().getConnectedPubKeyScript());
            // Find the signing key we'll need to use.
            byte[] connectedPubKeyHash = input.getOutpoint().getConnectedPubKeyHash();
            ECKey key = wallet.findKeyFromPubHash(connectedPubKeyHash);
            // This assert should never fire. If it does, it means the wallet is inconsistent.
            assert key != null : "Transaction exists in wallet that we cannot redeem: " + Utils.bytesToHexString(connectedPubKeyHash);
            // Keep the key around for the script creation step below.
            signingKeys[i] = key;
            // The anyoneCanPay feature isn't used at the moment.
            boolean anyoneCanPay = false;
            byte[] hash = hashTransactionForSignature(hashType, anyoneCanPay);
            // Set the script to empty again for the next input.
            input.setScriptBytes(TransactionInput.EMPTY_ARRAY);

            // Now sign for the output so we can redeem it. We use the keypair to sign the hash,
            // and then put the resulting signature in the script along with the public key (below).
            try {
                //usually 71-73 bytes
                ByteArrayOutputStream bos = new UnsafeByteArrayOutputStream(73);
                bos.write(key.sign(hash));
                bos.write((hashType.ordinal() + 1) | (anyoneCanPay ? 0x80 : 0));
                signatures[i] = bos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);  // Cannot happen.
            }
        }

        // Now we have calculated each signature, go through and create the scripts. Reminder: the script consists of
        // a signature (over a hash of the transaction) and the complete public key needed to sign for the connected
        // output.
        for (int i = 0; i < inputs.size(); i++) {
            TransactionInput input = inputs.get(i);
            assert input.getScriptBytes().length == 0;
            ECKey key = signingKeys[i];
            input.setScriptBytes(Script.createInputScript(signatures[i], key.getPubKey()));
        }

        // Every input is now complete.
    }

    /**
     * Calculates a signature hash, that is, a hash of a simplified form of the transaction. How exactly the transaction
     * is simplified is specified by the type and anyoneCanPay parameters.<p>
     *
     * You don't normally ever need to call this yourself. It will become more useful in future as the contracts
     * features of Bitcoin are developed.
     *
     * @param type Should be SigHash.ALL
     * @param anyoneCanPay should be false.
     */
    public byte[] hashTransactionForSignature(SigHash type, boolean anyoneCanPay) {
        try {
            ByteArrayOutputStream bos = new UnsafeByteArrayOutputStream(length == UNKNOWN_LENGTH ? 256 : length + 4);
            bitcoinSerialize(bos);
            // We also have to write a hash type.
            int hashType = type.ordinal() + 1;
            if (anyoneCanPay)
                hashType |= 0x80;
            Utils.uint32ToByteStreamLE(hashType, bos);
            // Note that this is NOT reversed to ensure it will be signed correctly. If it were to be printed out
            // however then we would expect that it is IS reversed.
            return doubleDigest(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    @Override
    protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
        uint32ToByteStreamLE(version, stream);
        stream.write(new VarInt(inputs.size()).encode());
        for (TransactionInput in : inputs)
            in.bitcoinSerialize(stream);
        stream.write(new VarInt(outputs.size()).encode());
        for (TransactionOutput out : outputs)
            out.bitcoinSerialize(stream);
        uint32ToByteStreamLE(lockTime, stream);
    }


    /**
     * @return the lockTime
     */
    public long getLockTime() {
        maybeParse();
        return lockTime;
    }

    /**
     * @param lockTime the lockTime to set
     */
    public void setLockTime(long lockTime) {
        unCache();
        this.lockTime = lockTime;
    }

    /**
     * @return the version
     */
    public long getVersion() {
        maybeParse();
        return version;
    }

    /**
     * @return a read-only list of the inputs of this transaction.
     */
    public List<TransactionInput> getInputs() {
        maybeParse();
        return Collections.unmodifiableList(inputs);
    }

    /**
     * @return a read-only list of the outputs of this transaction.
     */
    public List<TransactionOutput> getOutputs() {
        maybeParse();
        return Collections.unmodifiableList(outputs);
    }
    
    public synchronized TransactionConfidence getConfidence() {
        if (confidence == null) {
            confidence = new TransactionConfidence(this);
        }
        return confidence;
    }

    /** Check if the transaction has a known confidence */
    public boolean hasConfidence() {
        return confidence != null && confidence.getConfidenceType() != TransactionConfidence.ConfidenceType.UNKNOWN;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Transaction)) return false;
        Transaction t = (Transaction) other;

        return t.getHash().equals(getHash());
    }

    @Override
    public int hashCode() {
        return getHash().hashCode();
    }

    /**
     * Ensure object is fully parsed before invoking java serialization.  The backing byte array
     * is transient so if the object has parseLazy = true and hasn't invoked checkParse yet
     * then data will be lost during serialization.
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        maybeParse();
        out.defaultWriteObject();
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * returns whether this transaction was sent by this wallet
     * 
     * @param wallet
     * @return
     */
    public boolean sent(Wallet wallet) {
        for (TransactionInput in : inputs) {
            if (in.isMine(wallet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * returns whether this transaction uses one of the wallet's keys
     * 
     * @param wallet
     * @return
     */
    public boolean isMine(Wallet wallet) {
        try {
            for (TransactionOutput output : this.outputs) {
                // TODO: Handle more types of outputs, not just regular to
                // address outputs.
                if (output.getScriptPubKey().isSentToIP())
                    continue;
                // This is not thread safe as a key could be removed between the
                // call to isMine and receive.
                if (output.isMine(wallet)) {
                    return true;
                }
            }

            for (TransactionInput input : this.inputs) {
                try {
                    if (input.getScriptSig().isSentToIP())
                        continue;
                    // This is not thread safe as a key could be removed between the
                    // call to isPubKeyMine and receive.
                    if (input.isMine(wallet)) {
                        return true;
                    }
                } catch (ScriptException e) {
                    // cannot understand this transaction input - ignore
                    log.info(e.getMessage());
                }
            }
            return false;
        } catch (ScriptException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * make the TransactionOutputs spendable This is used in an intrawallet
     * transfer as what is spent from the senders's perspetive is avaiable to
     * spend from the recipients
     */
    public void markOutputsAsSpendable() {
        if (outputs != null) {
            for (TransactionOutput output : outputs) {
                if (output != null) {
                    output.markAsUnspent();
                }
            }
        }
    }
    
    /**
     * Calculate the fee for a spend
     * @param transaction Must be a spend as for a receive we do not have the connected output
     * @return BigInteger containing fee
     */
    public BigInteger calculateFee(Wallet wallet) {
        BigInteger totalOut = BigInteger.ZERO;    
        BigInteger totalIn = BigInteger.ZERO;
        for (TransactionInput input : getInputs()) {
            // This input is taking value from an transaction in our wallet. To
            // discover the value,
            // we must find the connected transaction.
            TransactionOutput connected = input.getConnectedOutput(wallet.unspent);
            if (connected == null)
                connected = input.getConnectedOutput(wallet.spent);
            if (connected == null)
                connected = input.getConnectedOutput(wallet.pending);
            if (connected == null)
                continue;
            totalIn = totalIn.add(connected.getValue());
        }
        List<TransactionOutput> outputs = getOutputs();
        for (TransactionOutput output : outputs) {
            totalOut = totalOut.add(output.getValue());
        }
        
        return totalIn.subtract(totalOut);
    }
    
    /**
     * Calculates the sum of the inputs that are spending coins with keys in the
     * wallet. This requires the transactions sending coins to those keys to be
     * in the wallet. This method will not attempt to download the blocks
     * containing the input transactions if the key is in the wallet but the
     * transactions are not.
     * 
     * This variant includes the change
     * 
     * @return sum in nanocoins.
     */
    public BigInteger getValueSentFromMeIncludingChange(Wallet wallet) throws ScriptException {
        maybeParse();
        // This is tested in WalletTest.
        BigInteger v = BigInteger.ZERO;
        for (TransactionInput input : inputs) {
            // This input is taking value from an transaction in our wallet. To
            // discover the value,
            // we must find the connected transaction.
            TransactionOutput connected = input.getConnectedOutput(wallet.unspent);
            if (connected == null)
                connected = input.getConnectedOutput(wallet.spent);
            if (connected == null)
                connected = input.getConnectedOutput(wallet.pending);
            if (connected == null)
                continue;
            v = v.add(connected.getValue());
        }
        return v;
    }
}
