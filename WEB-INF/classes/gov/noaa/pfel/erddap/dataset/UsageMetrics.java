package gov.noaa.pfel.erddap.dataset;

import com.cohort.util.String2;

public class UsageMetrics {


    public UsageMetrics(RequestDetails requestDetails) {

            updateVariables(requestDetails);

            String2.log(requestDetails.toString());

            // Check this
            String2.setupLog(false, false, metrics.jsonl);
        }

    public void updateVariables(RequestDetails requestDetails) {

        for(String s : EDDTable.dataFileTypeNames) {
            if(requestDetails.getVariables().endsWith(s)) {
                requestDetails.setUrl("");
            }

        }

    }

}


//(requestUrl.endsWith("login.html") && queryString.indexOf("nonce=") >= 0?
//        "?[CONFIDENTIAL]" :
//        EDStatic.questionQuery(queryString));

