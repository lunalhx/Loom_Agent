package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffStatsPayload {

    private Integer added;
    private Integer removed;
    private Integer modified;

}
