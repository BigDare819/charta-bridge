package dev.bridge.game;

import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How a bot picks the card to play.
 *
 * <p>Two levels live here. {@link BridgeStrength#EASY} is the behaviour the mod shipped with — the first
 * legal card in the fan, which is "follow suit from the top" because a hand is sorted ace-high.
 * {@link BridgeStrength#NORMAL} is a seat-aware rule set: second hand low, third hand high, the cheapest
 * card that wins, cash a sure winner when leading, ruff only when the ruff holds. {@link BridgeStrength#HARD}
 * puts that same idea behind a rollout: every legal card is played out to the end of the trick against
 * the best answer the rules can find, and the winner is the card whose <em>outcome</em> is best rather
 * than whose rank looks best.
 *
 * <h2>What "the best answer" means for the other three seats</h2>
 *
 * <p>Each modelled seat plays the cheapest card that takes the trick, or its lowest card when it cannot
 * — the same defensive rule the bot itself uses, so the rollout only ever discovers things the rules
 * already hinted at: that the ten wins this trick and the ace does not, that the ruff gets overruffed,
 * that partner's king already has it covered.
 *
 * <h2>Whole-hand knowledge, and why it is honest enough</h2>
 *
 * <p>The rollout plays the <em>real</em> remaining hands rather than sampling unseen ones, which is what
 * a double-dummy solver would do with perfect information. That is a good approximation in a trick game
 * — the auctions are short and the shapes are usually known — and the alternative costs far more per
 * decision than the difference it makes.
 *
 * <h2>Choosing the seat to model</h2>
 *
 * <p>The dummy is played by declarer, so the actor and the owner of the hand are different players while
 * the dummy is on turn. Everything that asks "whose card is this, and whose side is it on" goes through
 * {@code ownerSeat}, never through the acting player.
 */
public final class BridgePlay {

    /** Worth of taking the trick in the rollout, well above the 13 a card's own rank can cost. */
    private static final int TRICK_VALUE = 60;
    /** Worth of a sure winner when leading, and the penalty for giving the trick away. */
    private static final int CASH_VALUE = 40;
    private static final int GIVE_AWAY = 30;

    private BridgePlay() {
    }

    /**
     * Picks the card the owner of {@code ownerSeat}'s hand should play.
     *
     * @return a legal card, or {@code null} when the hand somehow has none
     */
    @Nullable
    public static Card choose(BridgeGame game, CardPlayer actor, int ownerSeat, BridgeStrength strength) {
        CardPlayer owner = game.playerAt(ownerSeat);
        List<Card> legal = new ArrayList<>();
        int slot = game.getTrickPile().getIndex();
        for (Card card : game.fullHand(owner).toList()) {
            if (game.canPlay(actor, new GamePlay(List.of(card), slot))) {
                legal.add(card);
            }
        }
        if (legal.isEmpty()) {
            return null;
        }
        if (strength == BridgeStrength.EASY || legal.size() == 1) {
            return legal.getFirst();
        }

        // Sorted low to high, so a tie at the same score is broken by the cheaper card: "win with the
        // ten" falls out of the ordering rather than needing a second comparison.
        legal.sort((a, b) -> Integer.compare(rankOrder(a), rankOrder(b)));

        Suit trump = BridgeBid.suit(game.getTrumpStrain());
        int partnerSeat = Math.floorMod(ownerSeat + 2, BridgeGame.PLAYERS);
        // One snapshot for the whole decision: getCurrentTrick() copies, and both the rule set and the
        // rollout want it once per candidate card.
        List<BridgeGame.PlayedCard> trick = game.getCurrentTrick();
        int played = trick.size();

        Card best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Card card : legal) {
            int score = strength == BridgeStrength.HARD
                    ? rollout(game, trick, ownerSeat, partnerSeat, trump, card)
                    : rule(game, trick, ownerSeat, partnerSeat, trump, card);
            if (best == null || score > bestScore) {
                bestScore = score;
                best = card;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------
    // The rule set (NORMAL)
    // ---------------------------------------------------------------------------------------------

    /**
     * How attractive one card is, judged on the trick as it stands.
     *
     * <p>Positive is good, and the scale is anchored so that winning with the ace still beats losing
     * with the two ({@link #CASH_VALUE} against at most 13 for the card's own cost).
     */
    private static int rule(BridgeGame game, List<BridgeGame.PlayedCard> trick, int ownerSeat,
                            int partnerSeat, @Nullable Suit trump, Card card) {
        Suit led = game.getLedSuit();
        int played = trick.size();
        int rank = rankOrder(card);
        boolean trumpCard = trump != null && card.suit() == trump;

        if (played == 0) {
            // Leading. Cash a winner if we have one; otherwise lead a low card towards the strength
            // rather than a high one into it, and keep out of trump while the side suits are unset.
            int score = isSureWinner(game, ownerSeat, trump, card) ? CASH_VALUE : 0;
            score += 13 - rank;
            if (trumpCard) {
                score -= 4;
            }
            return score;
        }

        if (card.suit() != led && !trumpCard) {
            // Discarding: the lowest card goes, and a discard from a suit we are already void in is free.
            return -10 + (13 - rank);
        }

        if (bestPlayedSeat(trick, led, trump) == partnerSeat) {
            // Partner already owns the trick; do not spend anything to take it off them.
            return -5 + (13 - rank);
        }

        boolean ruff = trumpCard && card.suit() != led;
        if (!beatsTrick(game, trick, trump, card)) {
            // Cannot win. Play the lowest of the suit we were told to follow, which is the whole of
            // "second hand low" and "fourth hand low when it does not matter".
            return -10 + (13 - rank);
        }
        // A ruff costs a trump, and a ruff that gets overruffed has thrown one away for nothing; only
        // take the trick when the fourth hand cannot beat the trump we would use.
        if (ruff && !trumpHolds(game, trick, trump, card)) {
            return 5 + (13 - rank);
        }

        int score = CASH_VALUE + TRICK_VALUE - rank;
        if (ruff) {
            score -= 6;
        }
        if (played == 3) {
            // Last to play: the cheapest winner really is enough, nobody can answer it.
            score += 8;
        } else if (played == 2) {
            // Third hand: high is right, and with a trump about a middle card may still be ruffed.
            score += 4;
        }
        return score;
    }

    // ---------------------------------------------------------------------------------------------
    // The rollout (HARD)
    // ---------------------------------------------------------------------------------------------

    /**
     * Plays this trick to its end with {@code chosen} led and scores the outcome.
     *
     * <p>Deliberately one trick deep. A full double-dummy search of a whole deal is an order of
     * magnitude more work for a bot that has to answer inside Charta's tick, and most of the value is
     * in "does this card actually win the trick once everybody has answered it" — the rest of the hand
     * is what the rule set is for.
     */
    private static int rollout(BridgeGame game, List<BridgeGame.PlayedCard> table, int seat,
                               int partnerSeat, @Nullable Suit trump, Card chosen) {
        List<BridgeGame.PlayedCard> trick = new ArrayList<>(table);
        trick.add(new BridgeGame.PlayedCard(seat, chosen));

        int leadSeat = trick.getFirst().seat();
        Suit led = trick.getFirst().card().suit();
        while (trick.size() < BridgeGame.PLAYERS) {
            int next = Math.floorMod(leadSeat + trick.size(), BridgeGame.PLAYERS);
            Card answer = answer(game, next, trump, led, trick);
            if (answer == null) {
                break;
            }
            trick.add(new BridgeGame.PlayedCard(next, answer));
        }

        int winner = trickWinner(trick, trump, led);
        boolean ours = winner == seat || winner == partnerSeat;
        int score = ours ? TRICK_VALUE : -GIVE_AWAY;
        // Never lead the ace when the two would have taken the trick just as well, and when losing it
        // anyway, lose the cheapest card that does it.
        return score - rankOrder(chosen);
    }

    /** The card {@code seat} would answer with: cheapest winner, otherwise the lowest of the suit. */
    @Nullable
    private static Card answer(BridgeGame game, int seat, @Nullable Suit trump, Suit led,
                               List<BridgeGame.PlayedCard> trick) {
        CardPlayer player = game.playerAt(seat);
        Set<Card> onTable = new HashSet<>();
        trick.forEach(played -> onTable.add(played.card()));

        List<Card> hand = new ArrayList<>();
        for (Card card : game.fullHand(player).toList()) {
            if (!onTable.contains(card)) {
                hand.add(card);
            }
        }
        if (hand.isEmpty()) {
            return null;
        }
        hand.sort((a, b) -> Integer.compare(rankOrder(a), rankOrder(b)));

        boolean hasLed = hand.stream().anyMatch(card -> card.suit() == led);
        for (Card card : hand) {
            if (hasLed && card.suit() != led) {
                continue;
            }
            if (card.suit() != led && (trump == null || card.suit() != trump)) {
                continue;
            }
            if (winsAgainst(trick, trump, card)) {
                return card;
            }
        }
        if (hasLed) {
            for (Card card : hand) {
                if (card.suit() == led) {
                    return card;
                }
            }
        }
        // Void and unable to ruff: throw the lowest card of the least useful suit, which for a model
        // is close enough to "the lowest card".
        return hand.getFirst();
    }

    private static boolean winsAgainst(List<BridgeGame.PlayedCard> trick, @Nullable Suit trump, Card card) {
        boolean cardTrump = trump != null && card.suit() == trump;
        for (BridgeGame.PlayedCard played : trick) {
            Card other = played.card();
            boolean otherTrump = trump != null && other.suit() == trump;
            if (otherTrump && !cardTrump) {
                return false;
            }
            if (otherTrump == cardTrump && other.suit().equals(card.suit())
                    && rankOrder(other) > rankOrder(card)) {
                return false;
            }
        }
        return true;
    }

    private static int trickWinner(List<BridgeGame.PlayedCard> trick, @Nullable Suit trump, Suit led) {
        BridgeGame.PlayedCard best = null;
        for (BridgeGame.PlayedCard played : trick) {
            if (best == null) {
                best = played;
                continue;
            }
            boolean cardTrump = trump != null && played.card().suit() == trump;
            boolean bestTrump = trump != null && best.card().suit() == trump;
            if (cardTrump != bestTrump) {
                if (cardTrump) {
                    best = played;
                }
                continue;
            }
            if (!played.card().suit().equals(led)) {
                continue;
            }
            if (rankOrder(played.card()) > rankOrder(best.card())) {
                best = played;
            }
        }
        return best == null ? -1 : best.seat();
    }

    // ---------------------------------------------------------------------------------------------
    // Trick state
    // ---------------------------------------------------------------------------------------------

    /** Whether {@code card} would take the trick as it stands; only meaningful once a suit is led. */
    private static boolean beatsTrick(BridgeGame game, List<BridgeGame.PlayedCard> trick,
                                      @Nullable Suit trump, Card card) {
        Suit led = game.getLedSuit();
        if (led == null || trick.isEmpty()) {
            return true;
        }
        if (card.suit() != led && (trump == null || card.suit() != trump)) {
            return false;
        }
        int bestSeat = bestPlayedSeat(trick, led, trump);
        if (bestSeat < 0) {
            return true;
        }
        Card best = trick.get(bestSeat).card();
        boolean bestTrump = trump != null && best.suit() == trump;
        boolean cardTrump = trump != null && card.suit() == trump;
        if (bestTrump != cardTrump) {
            return cardTrump;
        }
        if (!best.suit().equals(card.suit())) {
            return false;
        }
        return rankOrder(card) > rankOrder(best);
    }

    /**
     * A winner that wins on rank alone: no opponent can hold anything higher, and no opponent is left
     * with a trump to ruff it.
     */
    private static boolean isSureWinner(BridgeGame game, int seat, @Nullable Suit trump, Card card) {
        if (card.rank() != Ranks.ACE) {
            return false;
        }
        if (trump != null && card.suit() == trump) {
            return true;
        }
        for (int offset : new int[]{1, 3}) {
            CardPlayer opponent = game.playerAt(seat + offset);
            if (trump != null && game.fullHand(opponent).anyMatch(other -> other.suit() == trump)) {
                return false;
            }
        }
        return true;
    }

    /** Whether our trump, once played, survives the fourth hand. */
    private static boolean trumpHolds(BridgeGame game, List<BridgeGame.PlayedCard> trick,
                                      @Nullable Suit trump, Card card) {
        if (trump == null || trick.isEmpty()) {
            return false;
        }
        int fourth = Math.floorMod(trick.getFirst().seat() + 3, BridgeGame.PLAYERS);
        CardPlayer hand = game.playerAt(fourth);
        for (Card other : game.fullHand(hand).toList()) {
            if (other.suit() == trump && rankOrder(other) > rankOrder(card)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Index into {@code trick} of the card winning it, or {@code -1}.
     *
     * <p>An index rather than the card itself because {@code Card} equality compares the whole data
     * component map, which is far heavier than comparing two ints, and because the index identifies the
     * <em>seat</em> — the only thing most callers actually want to know.
     */
    private static int bestPlayedSeat(List<BridgeGame.PlayedCard> trick, @Nullable Suit led, @Nullable Suit trump) {
        int best = -1;
        for (int i = 0; i < trick.size(); i++) {
            Card card = trick.get(i).card();
            if (best < 0) {
                best = i;
                continue;
            }
            Card current = trick.get(best).card();
            boolean cardTrump = trump != null && card.suit() == trump;
            boolean bestTrump = trump != null && current.suit() == trump;
            if (cardTrump != bestTrump) {
                if (cardTrump) {
                    best = i;
                }
                continue;
            }
            if (led != null && !card.suit().equals(led)) {
                continue;
            }
            if (rankOrder(card) > rankOrder(current)) {
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Rank as an integer that puts the ace on top; see {@code BridgeGame#rankOrder}. */
    private static int rankOrder(Card card) {
        return card.rank() == Ranks.ACE ? Ranks.KING.ordinal() + 1 : card.rank().ordinal();
    }

}
