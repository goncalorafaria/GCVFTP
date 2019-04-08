package Transport.Unit;

import Estado.BitManipulator;

import java.nio.ByteBuffer;
import java.util.BitSet;

public abstract class Packet {

    public static Packet parse(byte[] udp_data){

        //System.out.println("got " + udp_data.length + " bytes ");

        boolean type = BitManipulator.msb(udp_data,0);

        if(type){
            System.out.print("control : \\ ");
            return ControlPacket.parseControl(udp_data);
        }else{
            System.out.print("data : ");
            return new DataPacket(udp_data);
        }
    }


    public abstract byte[] serialize();

}
