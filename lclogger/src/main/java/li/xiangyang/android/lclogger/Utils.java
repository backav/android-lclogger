package li.xiangyang.android.lclogger;

import java.util.ArrayList;

public class Utils {

    public static String[] splitStringToChunks(String source, int chunkLength) {
        if (chunkLength < 0) {
            throw new IllegalArgumentException("Chunk length must be greater or equal to zero!");
        }

        int srcLength = source.length();
        if (chunkLength == 0 || srcLength <= chunkLength) {
            return new String[]{source};
        }

        ArrayList<String> chunkBuffer = new ArrayList<String>();
        int splitSteps = srcLength / chunkLength + (srcLength % chunkLength > 0 ? 1 : 0);

        int lastCutPosition = 0;
        for (int i = 0; i < splitSteps; ++i) {

            if (i < splitSteps - 1) {
                chunkBuffer.add(source.substring(lastCutPosition, lastCutPosition + chunkLength));
            } else {
                chunkBuffer.add(source.substring(lastCutPosition));
            }

            lastCutPosition += chunkLength;
        }

        return chunkBuffer.toArray(new String[chunkBuffer.size()]);
    }
}
