package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentReplayStreamRequest implements Serializable {

    private static final long serialVersionUID = 5540212417467655629L;

    private Boolean includeChildren;

}
