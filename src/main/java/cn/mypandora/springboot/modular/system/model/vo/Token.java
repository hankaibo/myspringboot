package cn.mypandora.springboot.modular.system.model.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token
 *
 * @author hankaibo
 * @date 2019/6/22
 */
@ApiModel(value = "token对象", description = "用户登录成功之后返回的token对象")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Token {

    /**
     * token
     */
    @ApiModelProperty(value = "token值", example = "header.payload.xxx", required = true)
    private String token;

    /**
     * 角色
     */
    @ApiModelProperty(value = "角色值", example = "admin", required = true)
    private String role;

}
