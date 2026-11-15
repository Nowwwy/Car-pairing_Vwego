package ai.carpairing.app

import org.junit.Assert.*
import org.junit.Test

class ExpansionRulesTest {
    @Test fun validInvitationKeepsLeadingZeros(){assertEquals("001234",ExpansionRules.invitationCode("carpairing://join?code=001234"))}
    @Test fun rejectsOtherSitesAndAmbiguousInvitations(){
        for(value in listOf(null,"https://join?code=123456","carpairing://other?code=123456","carpairing://join?code=12345","carpairing://join?code=１２３４５６","carpairing://join/path?code=123456","carpairing://join?code=123456&code=654321","carpairing://join?code=123456#part","carpairing://user@join?code=123456","carpairing://join:80?code=123456","carpairing://join?code=%31%32%33%34%35%36")) assertNull(value,ExpansionRules.invitationCode(value))
    }
    @Test fun kindsHaveExplicitDwellDefaults(){assertEquals(15,ExpansionRules.defaultDwell("rest"));assertEquals(10,ExpansionRules.defaultDwell("fuel"));assertEquals(0,ExpansionRules.defaultDwell("parking"))}
}
