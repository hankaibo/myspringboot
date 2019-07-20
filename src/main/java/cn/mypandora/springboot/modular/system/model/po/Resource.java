package cn.mypandora.springboot.modular.system.model.po;

import cn.mypandora.springboot.modular.system.model.BaseTree;
import lombok.Data;

import javax.persistence.Table;

/**
 * Resource
 *
 * @author hankaibo
 * @date 2019/1/12
 */
@Data
@Table(name = "sys_resource")
public class Resource extends BaseTree {

    private static final long serialVersionUID = -2978029310184453966L;

    /**
     * 资源编码
     */
    private String code;

    /**
     * 状态
     */
    private Integer status;

    /**
     * URI
     */
    private String uri;

    /**
     * 类型
     */
    private Integer type;

    /**
     * 方法
     */
    private String method;

    /**
     * 图标
     */
    private String icon;

    /**
     * 描述
     */
    private String description;

}