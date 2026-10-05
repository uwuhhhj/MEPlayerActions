package com.simmc.meplayeractions.client.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthorFormLayoutTest {
    @Test void longRadioLabelsUseOneColumnAndAllChoicesRemainReachable() {
        int columns=AuthorFormLayout.radioColumns(115,140,64);
        assertEquals(1,columns);assertEquals(64,AuthorFormLayout.radioRows(64,columns));
        assertEquals(64*22-110,AuthorFormLayout.clampScroll(Integer.MAX_VALUE,64*22,110));
    }
    @Test void shortRadioChoicesWrapAndNeverAddEmptyColumns() {
        assertEquals(4,AuthorFormLayout.radioColumns(120,10,6));
        assertEquals(2,AuthorFormLayout.radioRows(6,4));
        assertEquals(2,AuthorFormLayout.radioColumns(720,8,2));
    }
    @Test void emptyOrTinyLayoutsKeepAValidScrollRange() {
        assertEquals(1,AuthorFormLayout.radioColumns(0,0,0));
        assertEquals(0,AuthorFormLayout.radioRows(0,0));
        assertEquals(0,AuthorFormLayout.clampScroll(-20,100,80));
        assertEquals(20,AuthorFormLayout.clampScroll(30,100,80));
        assertEquals(0,AuthorFormLayout.clampScroll(20,40,80));
    }
}
