package Transport.ControlPacketTypes;

import Common.BitManipulator;
import Transport.GCVConnection;
import Transport.Unit.ControlPacket;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class NOPE extends ControlPacket {

    public static int size = ControlPacket.header_size + 4 * 2 * (GCVConnection.max_loss_list_size + 1);

    private List<Integer> losslist;

    public NOPE( BitManipulator extrator ) {
        super(ControlPacket.Type.NOPE, extrator.getShort()/*extended*/, extrator.getInt());
        /*extract the loss list*/
        losslist = new ArrayList<>();
        while (true) {
            try {
                Integer val = extrator.getInt();
                losslist.add(val);
            }catch(BufferOverflowException| BufferUnderflowException e){
                break;
            }
        }
    }

    public int size(){
        return ControlPacket.header_size + losslist.size()*4;
    }

    public NOPE(short extendedtype, int timestamp, List<Integer> losslist){
        super(ControlPacket.Type.NOPE,extendedtype,timestamp);
        this.losslist = losslist;
    }

    public byte[] extendedSerialize( BitManipulator extractor ){

        for(Integer val : this.losslist )
            extractor.put(val);

        return extractor.array();
    }

    public List<Integer> getLossList(){

        List<Integer> extended_loss_list =  new LinkedList<>();
        for(int i=0; i< this.losslist.size(); i=i+2){
            int min = this.losslist.get(i);
            int max = this.losslist.get(i+1);

            for(int j = min; j <= max; j++)
                extended_loss_list.add(j);
        }

        return extended_loss_list;
    }
}
