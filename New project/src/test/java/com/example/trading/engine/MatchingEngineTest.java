package com.example.trading.engine;
import com.example.trading.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MatchingEngineTest {
 private MatchingEngine engine(){MatchingEngine e=new MatchingEngine(Map.of("AAPL",10000L));e.registerAccount("buyer",100000,0);e.registerAccount("seller",200000,10);return e;}
 @Test void matchesAtRestingPrice(){MatchingEngine e=engine();e.submitOrder("seller","AAPL",Side.SELL,OrderType.LIMIT,10000,5);Order o=e.submitOrder("buyer","AAPL",Side.BUY,OrderType.LIMIT,10100,5);assertEquals("FILLED",o.status);assertEquals(1,e.trades().size());assertEquals(10000,e.trades().get(0).price);assertEquals(5,e.account("buyer").holdings.get("AAPL"));}
 @Test void priceTimePriority(){MatchingEngine e=engine();e.submitOrder("seller","AAPL",Side.SELL,OrderType.LIMIT,10000,5);e.submitOrder("seller","AAPL",Side.SELL,OrderType.LIMIT,10000,5);e.submitOrder("buyer","AAPL",Side.BUY,OrderType.LIMIT,10000,6);assertEquals(5,e.trades().get(0).quantity);assertEquals(1,e.trades().get(1).quantity);}
 @Test void rejectsOversellAndSupportsCancel(){MatchingEngine e=engine();assertThrows(IllegalArgumentException.class,()->e.submitOrder("seller","AAPL",Side.SELL,OrderType.LIMIT,10000,21));Order o=e.submitOrder("seller","AAPL",Side.SELL,OrderType.LIMIT,11000,5);e.cancelOrder("seller",o.id);assertEquals("CANCELLED",e.orders().get(0).status);}
}
