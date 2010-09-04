/* LanguageTool, a natural language style checker 
 * Copyright (C) 2010 Marcin Miłkowski (http://www.languagetool.org)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package de.danielnaber.languagetool.rules.ru;

import java.util.ResourceBundle;
import java.util.regex.Pattern;

import de.danielnaber.languagetool.AnalyzedTokenReadings;
import de.danielnaber.languagetool.Language;
import de.danielnaber.languagetool.rules.GenericUnpairedBracketsRule;

public class RussianUnpairedBracketsRule extends GenericUnpairedBracketsRule {

  private static final String[] RU_START_SYMBOLS = { "[", "(", "{", "„", "«", "\"", "'" };
  private static final String[] RU_END_SYMBOLS   = { "]", ")", "}", "“", "»", "\"", "'" };

  private static final Pattern NUMERALS_RU = Pattern
  .compile("(?i)\\d{1,2}?[а-я]*|[а-я]|[А-Я]|[а-я][а-я]|[А-Я][А-Я]");

  
  protected boolean isNoException(final String token,
      final AnalyzedTokenReadings[] tokens, final int i, final int j,
      final boolean precSpace,
      final boolean follSpace) {
    // exception for Russian bullets: а), б), Д)..., ДД), аа) and 1а).
    if (i > 1 && endSymbols[j].equals(")") &&
        NUMERALS_RU.matcher(tokens[i - 1].getToken()).matches() && 
        !(!symbolStack.empty() && "(".equals(symbolStack.peek().symbol))) {
       return false;
     }
    return true;
  }
  
  public RussianUnpairedBracketsRule(final ResourceBundle messages,
      final Language language) {
    super(messages, language);
    startSymbols = RU_START_SYMBOLS;
    endSymbols = RU_END_SYMBOLS;
  }

  public String getId() {
    return "RU_UNPAIRED_BRACKETS";
  }
  
}
