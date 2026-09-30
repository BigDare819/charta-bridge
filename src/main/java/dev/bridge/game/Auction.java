package dev.bridge.game;

import java.util.ArrayList;
import java.util.List;

/**
 * The bidding auction: who called what, in order, plus the enquiries the game and the screen need —
 * whose turn it is, what the current contract is, and whether the whole thing is finished.
 *
 * <p>Only the call codes are stored. Every call's seat follows from the dealer and the position in
 * the list ({@link #seatOf}), which is what lets the server send nothing but a flat list of codes to
 * the client and have the client rebuild an identical auction — including for legality checks, so an
 * illegal button can be greyed out without a round trip.
 *
 * <p>That "seat equals dealer + index" identity is what keeps {@link #call} honest: a call is only ever
 * accepted from the seat that is next to speak, so the {@code seat} argument is checked rather than
 * remembered.
 *
 * <h2>Declarer</h2>
 *
 * <p>{@link #declarer()} is not the highest bidder: it is the first player of the winning
 * <em>partnership</em> who named the winning strain, which is the actual rule of the game, and it
 * matters because the declarer's left-hand opponent leads the first trick.
 */
public final class Auction {

    private final List<Integer> codes = new ArrayList<>();

    private final int dealer;

    private int consecutivePasses;
    /** 0 = not doubled, 1 = doubled, 2 = redoubled. */
    private int doubling;
    private int highest;
    private int highestSeat = -1;

    public Auction(int dealer) {
        this.dealer = Math.floorMod(dealer, 4);
    }

    public int size() {
        return codes.size();
    }

    /** Call at {@code index}, oldest first. */
    public int code(int index) {
        return codes.get(index);
    }

    public int seatOf(int index) {
        return Math.floorMod(dealer + index, 4);
    }

    /** Seat that has to call next. */
    public int nextSeat() {
        return Math.floorMod(dealer + codes.size(), 4);
    }

    /** Highest contract so far, or {@link BridgeBid#PASS} when nobody has bid yet. */
    public int highest() {
        return highest;
    }

    public int highestSeat() {
        return highestSeat;
    }

    public int strain() {
        return BridgeBid.strain(highest);
    }

    public int level() {
        return BridgeBid.level(highest);
    }

    public int doubling() {
        return doubling;
    }

    /** True once at least one player has named a contract. */
    public boolean hasContract() {
        return highest >= BridgeBid.FIRST_CONTRACT;
    }

    public boolean isPassedOut() {
        return !hasContract() && consecutivePasses >= 4;
    }

    public boolean isOver() {
        return isPassedOut() || (hasContract() && consecutivePasses >= 3);
    }

    /**
     * Declarer of the contract on the table: the first seat of the contract-holding side that named
     * this strain.
     *
     * @return the seat, or {@code -1} when there is no contract
     */
    public int declarer() {
        if (!hasContract()) {
            return -1;
        }
        int strain = strain();
        int side = Math.floorMod(highestSeat, 2);
        for (int i = 0; i < codes.size(); i++) {
            int call = codes.get(i);
            if (BridgeBid.isContract(call) && BridgeBid.strain(call) == strain && Math.floorMod(seatOf(i), 2) == side) {
                return seatOf(i);
            }
        }
        return highestSeat;
    }

    /** {@code true} when the call would be accepted by a real auction. */
    public boolean isLegal(int call, int seat) {
        if (call == BridgeBid.PASS) {
            return true;
        }
        if (call == BridgeBid.DOUBLE) {
            // Only an opponent of the contract holder, and only while it is undoubled.
            return hasContract() && doubling == 0 && Math.floorMod(highestSeat - seat, 2) != 0;
        }
        if (call == BridgeBid.REDOUBLE) {
            // Only the contract holder's own side, and only after a double.
            return hasContract() && doubling == 1 && Math.floorMod(highestSeat - seat, 2) == 0;
        }
        return BridgeBid.isContract(call) && call > highest;
    }

    /**
     * Records a call and advances the derived state.
     *
     * @return {@code true} when the call was legal and has been recorded
     */
    public boolean call(int call, int seat) {
        if (!isLegal(call, seat)) {
            return false;
        }
        codes.add(call);
        consecutivePasses = call == BridgeBid.PASS ? consecutivePasses + 1 : 0;
        if (call == BridgeBid.PASS) {
            return true;
        }

        if (BridgeBid.isContract(call)) {
            highest = call;
            highestSeat = Math.floorMod(seat, 4);
            doubling = 0;
        } else if (call == BridgeBid.DOUBLE) {
            doubling = 1;
        } else {
            doubling = 2;
        }
        return true;
    }

}
