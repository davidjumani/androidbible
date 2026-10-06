package yuku.alkitab.base.util;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.SparseBooleanArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import yuku.alkitab.debug.BuildConfig;
import yuku.alkitab.model.Book;
import yuku.alkitab.model.Version;
import yuku.alkitab.util.Ari;
import yuku.alkitab.util.IntArrayList;

public class SearchEngine {
    static final String TAG = SearchEngine.class.getSimpleName();

    /**
     * Contains processed tokens that is more efficient to be passed in to methods here such as
     * {@link #hilite(CharSequence, ReadyTokens, int)} and {@link #satisfiesTokens(String, ReadyTokens)}.
     */
    public static class ReadyTokens {
        final int token_count;
        final boolean[] hasPlusses;
        /**
         * Already without plusses
         */
        final String[] tokens;
        final String[][] multiwords_tokens;
        /** Match against the original text instead of the lowercased one. */
        final boolean caseSensitive;
        /** First word of a phrase may start mid-word. */
        final boolean[] looseStarts;
        /** Last word of a phrase may end mid-word. */
        final boolean[] looseEnds;
        /** ALL-CAPS form of each token, only when {@link #caseSensitive}. */
        final String[] upperTokens;
        final String[][] upperMultiwordsTokens;

        public ReadyTokens(final String[] tokens) {
            this(tokens, false, null, null);
        }

        ReadyTokens(final String[] tokens, final boolean caseSensitive, @Nullable final boolean[] looseStarts, @Nullable final boolean[] looseEnds) {
            final int token_count = tokens.length;
            this.token_count = token_count;
            this.hasPlusses = new boolean[token_count];
            this.tokens = new String[token_count];
            this.multiwords_tokens = new String[token_count][];
            this.caseSensitive = caseSensitive;
            this.looseStarts = looseStarts != null ? looseStarts : new boolean[token_count];
            this.looseEnds = looseEnds != null ? looseEnds : new boolean[token_count];
            this.upperTokens = new String[token_count];
            this.upperMultiwordsTokens = new String[token_count][];

            for (int i = 0; i < token_count; i++) {
                final String token = tokens[i];
                if (QueryTokenizer.isPlussedToken(token)) {
                    this.hasPlusses[i] = true;

                    final String tokenWithoutPlus = QueryTokenizer.tokenWithoutPlus(token);
                    this.tokens[i] = tokenWithoutPlus;

                    final String[] multiword = QueryTokenizer.tokenizeMultiwordToken(tokenWithoutPlus);
                    if (multiword != null) {
                        this.multiwords_tokens[i] = multiword;
                    }
                } else {
                    this.tokens[i] = token;
                }

                if (caseSensitive) {
                    this.upperTokens[i] = upperAlternative(this.tokens[i]);
                    final String[] multiword = this.multiwords_tokens[i];
                    if (multiword != null) {
                        final String[] uppers = new String[multiword.length];
                        for (int j = 0; j < multiword.length; j++) {
                            uppers[j] = upperAlternative(multiword[j]);
                        }
                        this.upperMultiwordsTokens[i] = uppers;
                    }
                }
            }
        }

        /** Longest token first, since it narrows the results fastest. */
        @NonNull
        public static ReadyTokens forQuery(final String query, @NonNull final SearchOptions options) {
            final String[] rawTokens = QueryTokenizer.tokenize(query, !options.getMatchCapitals());

            if (options.getExactPhrase() && rawTokens.length >= 2) {
                final List<String> words = new ArrayList<>();
                for (final String rawToken : rawTokens) {
                    words.addAll(QueryTokenizer.splitWords(QueryTokenizer.tokenWithoutPlus(rawToken)));
                }
                if (words.size() >= 2) {
                    final boolean looseStart = !options.getWholeWords() && !QueryTokenizer.isPlussedToken(rawTokens[0]);
                    final boolean looseEnd = !options.getWholeWords() && !QueryTokenizer.isPlussedToken(rawTokens[rawTokens.length - 1]);
                    return new ReadyTokens(new String[]{"+" + String.join(" ", words)}, options.getMatchCapitals(), new boolean[]{looseStart}, new boolean[]{looseEnd});
                }
            }

            String[] tokens = rawTokens.clone();
            if (options.getWholeWords()) {
                for (int i = 0; i < tokens.length; i++) {
                    if (!QueryTokenizer.isPlussedToken(tokens[i])) tokens[i] = "+" + tokens[i];
                }
            }

            // sort by word length, then alphabetically
            Arrays.sort(tokens, (object1, object2) -> {
                final int len1 = object1.length();
                final int len2 = object2.length();

                if (len1 > len2) return -1;
                if (len1 == len2) {
                    return object1.compareTo(object2);
                }
                return 1;
            });

            // remove duplicates
            final ArrayList<String> atokens = new ArrayList<>();
            String last = null;
            for (String token : tokens) {
                if (!token.equals(last)) {
                    atokens.add(token);
                }
                last = token;
            }

            return new ReadyTokens(atokens.toArray(new String[0]), options.getMatchCapitals(), null, null);
        }

        /**
         * ALL-CAPS version of the word, so "Lord" also finds "LORD".
         * Null if uppercasing changes the length (like German "ß" becoming "SS").
         */
        @Nullable
        private static String upperAlternative(final String word) {
            final String upper = word.toUpperCase(Locale.ROOT);
            return !upper.equals(word) && upper.length() == word.length() ? upper : null;
        }
    }

    public static IntArrayList searchByGrep(final Version version, final SearchEngineQuery query) {
        final ReadyTokens rt = ReadyTokens.forQuery(query.query_string, query.options);
        AppLog.d(TAG, "tokens = " + Arrays.toString(rt.tokens));

        // really search
        IntArrayList result = null;

        for (int i = 0; i < rt.token_count; i++) {
            final IntArrayList prev = result;

            {
                long ms = System.currentTimeMillis();
                result = searchByGrepInside(version, rt, i, prev, query.bookIds);
                AppLog.d(TAG, "search token '" + rt.tokens[i] + "' needed: " + (System.currentTimeMillis() - ms) + " ms");
            }

            if (prev != null) {
                AppLog.d(TAG, "Will intersect " + prev.size() + " elements with " + result.size() + " elements...");
                result = intersect(prev, result);
                AppLog.d(TAG, "... the result is " + result.size() + " elements");
            }
        }

        return result;
    }

    private static IntArrayList intersect(IntArrayList a, IntArrayList b) {
        IntArrayList res = new IntArrayList(a.size());

        int[] aa = a.buffer();
        int[] bb = b.buffer();
        int alen = a.size();
        int blen = b.size();

        int apos = 0;
        int bpos = 0;

        while (true) {
            if (apos >= alen) break;
            if (bpos >= blen) break;

            int av = aa[apos];
            int bv = bb[bpos];

            if (av == bv) {
                res.add(av);
                apos++;
                bpos++;
            } else if (av > bv) {
                bpos++;
            } else { // av < bv
                apos++;
            }
        }

        return res;
    }

    /**
     * Return the next ari (with only book and chapter) after the lastAriBc by scanning the source starting from pos.
     *
     * @param ppos pointer to pos. pos will be changed to ONE AFTER THE FOUND POSITION. So do not do another increment (++) outside this method.
     */
    private static int nextAri(IntArrayList source, int[] ppos, int lastAriBc) {
        int[] s = source.buffer();
        int len = source.size();
        int pos = ppos[0];

        while (true) {
            if (pos >= len) return 0x0;

            int curAri = s[pos];
            int curAriBc = Ari.toBookChapter(curAri);

            if (curAriBc != lastAriBc) {
                // found!
                pos++;
                ppos[0] = pos;
                return curAriBc;
            } else {
                // still the same one, move to next.
                pos++;
            }
        }
    }

    static IntArrayList searchByGrepInside(final Version version, final ReadyTokens rt, final int tokenIndex, final IntArrayList source, final SparseBooleanArray bookIds) {
        final IntArrayList res = new IntArrayList();

        if (source == null) {
            for (Book book : version.getConsecutiveBooks()) {
                if (!bookIds.get(book.bookId, false)) {
                    continue; // the book is not included in selected books to be searched
                }

                for (int chapter_1 = 1; chapter_1 <= book.chapter_count; chapter_1++) {
                    // try to find it wholly in a chapter
                    final int ariBc = Ari.encode(book.bookId, chapter_1, 0);
                    searchByGrepForOneChapter(version, book, chapter_1, rt, tokenIndex, ariBc, res);
                }

                if (BuildConfig.DEBUG) AppLog.d(TAG, "searchByGrepInside book " + book.shortName + " done. res.size = " + res.size());
            }
        } else {
            // search only on book-chapters that are in the source
            int count = 0; // for stats

            int[] ppos = new int[1];
            int curAriBc = 0x000000;

            while (true) {
                curAriBc = nextAri(source, ppos, curAriBc);
                if (curAriBc == 0) break; // no more

                // No need to check null book, because we go here only after searching a previous token which is based on
                // getConsecutiveBooks, which is impossible to have null books.
                final Book book = version.getBook(Ari.toBook(curAriBc));
                final int chapter_1 = Ari.toChapter(curAriBc);

                searchByGrepForOneChapter(version, book, chapter_1, rt, tokenIndex, curAriBc, res);

                count++;
            }

            if (BuildConfig.DEBUG) AppLog.d(TAG, "searchByGrepInside book with source " + source.size() + " needed to read as many as " + count + " book-chapter. res.size=" + res.size());
        }

        return res;
    }

    /**
     * @param res (output) result aris
     * @param ariBc book-chapter ari, with verse must be set to 0
     */
    private static void searchByGrepForOneChapter(final Version version, final Book book, final int chapter_1, final ReadyTokens rt, final int tokenIndex, final int ariBc, final IntArrayList res) {
        // This is a string of one chapter with verses joined by 0x0a ('\n')
        final String oneChapter = rt.caseSensitive
            ? version.loadChapterTextWithoutSplit(book, chapter_1)
            : version.loadChapterTextLowercasedWithoutSplit(book, chapter_1);
        if (oneChapter == null) {
            return;
        }

        int verse_0 = 0;
        int lastV = -1;

        final int[] consumedLengthPtr = {0};

        int posToken = indexOfToken(oneChapter, rt, tokenIndex, 0, true, consumedLengthPtr);
        if (posToken == -1) {
            // initial search does not return results. It means the whole chapter does not contain the token.
            return;
        }

        int posN = oneChapter.indexOf(0x0a);

        while (true) {
            if (posN < posToken) {
                verse_0++;
                posN = oneChapter.indexOf(0x0a, posN + 1);
                if (posN == -1) {
                    return;
                }
            } else {
                if (verse_0 != lastV) {
                    res.add(ariBc + verse_0 + 1); // +1 to make it verse_1
                    lastV = verse_0;
                }
                posToken = indexOfToken(oneChapter, rt, tokenIndex, posToken + consumedLengthPtr[0], true, consumedLengthPtr);
                if (posToken == -1) {
                    return;
                }
            }
        }
    }

    /**
     * @param consumedLengthPtr (output) length of the match in <code>text</code>
     * @return -1 or position of the token
     */
    private static int indexOfToken(final String text, final ReadyTokens rt, final int tokenIndex, final int start, final boolean isNewlineDelimitedText, final int[] consumedLengthPtr) {
        final String[] multiword = rt.multiwords_tokens[tokenIndex];
        if (multiword != null) {
            return indexOfMultiword(text, multiword, rt.upperMultiwordsTokens[tokenIndex], start, isNewlineDelimitedText, rt.looseStarts[tokenIndex], rt.looseEnds[tokenIndex], consumedLengthPtr);
        }

        final String token = rt.tokens[tokenIndex];
        final boolean whole = rt.hasPlusses[tokenIndex];
        consumedLengthPtr[0] = token.length();
        return indexOfWord(text, token, rt.upperTokens[tokenIndex], start, whole, whole);
    }

    /**
     * Unless <code>rt</code> is case-sensitive, <code>s</code> must already be lowercased.
     */
    public static boolean satisfiesTokens(final String s, @NonNull final ReadyTokens rt) {
        final int[] consumedLengthPtr = {0};
        for (int i = 0; i < rt.token_count; i++) {
            if (indexOfToken(s, rt, i, 0, false, consumedLengthPtr) == -1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds <code>word</code> or <code>upperWord</code>, optionally with no letter or digit right before/after it.
     * This works well only if the word is not a multiword.
     *
     * @param text haystack
     * @param word needle
     * @param start start at character
     * @return -1 or position of the word
     */
    private static int indexOfWord(String text, String word, @Nullable String upperWord, int start, boolean wholeLeft, boolean wholeRight) {
        final int len = text.length();

        while (true) {
            final int pos = indexOfEither(text, word, upperWord, start);
            if (pos == -1) return -1;

            // check left
            // [pos] [charat pos-1] [charat pos-2]
            //  0                                    ok
            // >1       alnum            '@'         ok
            // >1       alnum          not '@'       ng
            // >0       alnum                        ng
            // >0     not alnum                      ok
            if (wholeLeft && pos != 0 && Character.isLetterOrDigit(text.charAt(pos - 1))) {
                if (pos != 1 && text.charAt(pos - 2) == '@') {
                    // oh, before this word there is a tag. Then it is OK.
                } else {
                    start = pos + 1; // give up
                    continue;
                }
            }

            // check right
            int end = pos + word.length();
            // [end]   [charat end]
            // len         *         ok
            // != len    alnum       ng
            // != len  not alnum     ok
            if (wholeRight && end != len && Character.isLetterOrDigit(text.charAt(end))) {
                start = pos + 1; // give up
                continue;
            }

            // passed
            return pos;
        }
    }

    /** <code>alternative</code> must be as long as <code>word</code>. */
    private static int indexOfEither(String text, String word, @Nullable String alternative, int start) {
        final int a = text.indexOf(word, start);
        if (alternative == null) return a;
        final int b = text.indexOf(alternative, start);
        if (a == -1) return b;
        if (b == -1) return a;
        return Math.min(a, b);
    }

    /**
     * This looks for a multiword that is surrounded by non-letter characters.
     * This works for multiword because it tries to strip tags and punctuations from the text before matching.
     *
     * @param text haystack.
     * @param multiword multiword that has been split into words. Must have at least one element.
     * @param upperMultiword ALL-CAPS form of each word, or null.
     * @param start character index of text to start searching from
     * @param isNewlineDelimitedText <code>text</code> has '\n' as delimiter between verses. <code>multiword</code> cannot be searched across different verses.
     * @param looseStart first word may start mid-word.
     * @param looseEnd last word may end mid-word.
     * @param consumedLengthPtr (length-1 array output) how many characters matched from the source text to satisfy the multiword. Will be 0 if this method returns -1.
     * @return -1 or position of the multiword.
     */
    private static int indexOfMultiword(String text, String[] multiword, @Nullable String[] upperMultiword, int start, boolean isNewlineDelimitedText, boolean looseStart, boolean looseEnd, @Nullable int[] consumedLengthPtr) {
        final int len = text.length();
        final int lastIndex = multiword.length - 1;
        final String firstWord = multiword[0];

        findAllWords:
        while (true) {
            final int firstPos = indexOfWord(text, firstWord, upperMultiword != null ? upperMultiword[0] : null, start, !looseStart, !(looseEnd && lastIndex == 0));
            if (firstPos == -1) {
                // not even the first word is found, so we give up
                if (consumedLengthPtr != null) consumedLengthPtr[0] = 0;
                return -1;
            }

            int pos = firstPos + firstWord.length();

            // find the next words, but we want to consume everything after the previous word that is
            // not eligible as search characters, which are tags and non-letters.
            for (int i = 1; i <= lastIndex; i++) {
                final int posBeforeConsume = pos;
                // consume!
                while (pos < len) {
                    final char c = text.charAt(pos);
                    if (c == '@') {
                        if (pos == len - 1) {
                            // bad data (nothing after '@')
                        } else {
                            pos++;
                            final char d = text.charAt(pos);
                            if (d == '<') {
                                final int closingTagStart = text.indexOf("@>", pos + 1);
                                if (closingTagStart == -1) {
                                    // bad data (no closing tag)
                                } else {
                                    pos = closingTagStart + 1;
                                }
                            } else {
                                // single-letter formatting code, move on...
                            }
                        }
                    } else if (Character.isLetterOrDigit(c)) {
                        break;
                    } else if (isNewlineDelimitedText && c == '\n') {
                        // can't cross verse boundary, so we give up and try from beginning again
                        start = pos + 1;
                        continue findAllWords;
                    } else {
                        // non-letter, move on...
                    }

                    pos++;
                }

                if (BuildConfig.DEBUG) {
                    AppLog.d(TAG, "=========================");
                    AppLog.d(TAG, "multiword: " + Arrays.toString(multiword));
                    AppLog.d(TAG, "text     : #" + text.substring(Math.max(0, posBeforeConsume - multiword[i - 1].length()), Math.min(len, posBeforeConsume + 80)) + "#");
                    AppLog.d(TAG, "skipped  : #" + text.substring(posBeforeConsume, pos) + "#");
                    AppLog.d(TAG, "=========================////");
                }

                // left side is already a boundary, we just skipped non-letters
                final String word = multiword[i];
                final String upperWord = upperMultiword != null ? upperMultiword[i] : null;
                final boolean matchesHere = text.startsWith(word, pos) || (upperWord != null && text.startsWith(upperWord, pos));
                final int end = pos + word.length();
                final boolean wholeRight = i != lastIndex || !looseEnd;
                if (!matchesHere || (wholeRight && end != len && Character.isLetterOrDigit(text.charAt(end)))) {
                    // subsequent words is not found at the correct position, so loop from beginning again
                    start = pos;
                    continue findAllWords;
                }

                // prepare for next iteration
                pos = end;
            }

            // all words are found!
            if (consumedLengthPtr != null) consumedLengthPtr[0] = pos - firstPos;
            return firstPos;
        }
    }

    public static SpannableStringBuilder hilite(final CharSequence s, final ReadyTokens rt, int hiliteColor) {
        final SpannableStringBuilder res = new SpannableStringBuilder(s);

        if (rt == null) {
            return res;
        }

        final int token_count = rt.token_count;

        // lowercase without changing any offsets
        final String plainText;
        if (rt.caseSensitive) {
            plainText = s.toString();
        } else {
            final char[] newString = new char[s.length()];
            for (int i = 0, len = s.length(); i < len; i++) {
                final char c = s.charAt(i);
                if (c >= 'A' && c <= 'Z') {
                    newString[i] = (char) (c | 0x20);
                } else {
                    newString[i] = Character.toLowerCase(c);
                }
            }
            plainText = new String(newString);
        }

        int pos = 0;
        final int[] attempts = new int[token_count];
        final int[] consumedLengths = new int[token_count];

        // temp buf
        final int[] consumedLengthPtr = {0};
        while (true) {
            for (int i = 0; i < token_count; i++) {
                attempts[i] = indexOfToken(plainText, rt, i, pos, false, consumedLengthPtr);
                consumedLengths[i] = consumedLengthPtr[0];
            }

            // from the attempts above, find the earliest
            int minpos = Integer.MAX_VALUE;
            int mintokenindex = -1;

            for (int i = 0; i < token_count; i++) {
                if (attempts[i] >= 0) { // not -1 which means not found
                    if (attempts[i] < minpos) {
                        minpos = attempts[i];
                        mintokenindex = i;
                    }
                }
            }

            if (mintokenindex == -1) {
                break; // no more
            }

            final int topos = minpos + consumedLengths[mintokenindex];
            res.setSpan(new StyleSpan(Typeface.BOLD), minpos, topos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            res.setSpan(new ForegroundColorSpan(hiliteColor), minpos, topos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pos = topos;
        }

        return res;
    }
}
