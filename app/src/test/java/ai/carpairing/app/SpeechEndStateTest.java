package ai.carpairing.app;
public class SpeechEndStateTest {
 @org.junit.Test public void repeatedEndpointsAndConsecutiveRecordings() {
  SpeechEndState s=new SpeechEndState();
  s.epd(100,900,1000); s.epd(100,900,2100);
  if(!s.shouldEnd(2300)) throw new AssertionError("repeated end events must not extend wait");
  s.epd(1000,0,2400);
  if(s.shouldEnd(4000)) throw new AssertionError("new speech must cancel end timer");
  s.epd(1000,1800,4200);
  if(s.shouldEnd(4300)||!s.shouldEnd(5500)) throw new AssertionError("allow a short pause before ending");
  SpeechEndState second=new SpeechEndState();
  if(second.shouldEnd(10000)) throw new AssertionError("second recording must start clean");
  second.epd(0,0,10000);
  if(second.shouldEnd(12000)) throw new AssertionError("empty EPD is not speech");
  System.out.println("5 endpoint checks passed");
 }
}
